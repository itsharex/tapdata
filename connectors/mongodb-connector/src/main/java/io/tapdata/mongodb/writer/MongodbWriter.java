package io.tapdata.mongodb.writer;

import com.mongodb.ConnectionString;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import io.tapdata.constant.AppType;
import io.tapdata.entity.event.dml.TapDeleteRecordEvent;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.utils.cache.KVMap;
import io.tapdata.mongodb.MongodbUtil;
import io.tapdata.mongodb.entity.MongodbConfig;
import io.tapdata.mongodb.reader.MongodbV4StreamReader;
import io.tapdata.mongodb.util.MongodbLookupUtil;
import io.tapdata.mongodb.writer.error.BulkWriteErrorCodeHandlerEnum;
import io.tapdata.pdk.apis.entity.ConnectionOptions;
import io.tapdata.pdk.apis.entity.WriteListResult;
import io.tapdata.pdk.apis.entity.merge.MergeInfo;
import io.tapdata.pdk.apis.entity.merge.MergeLookupResult;
import io.tapdata.pdk.apis.entity.merge.MergeTableProperties;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.bson.Document;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.tapdata.base.ConnectorBase.writeListResult;

/**
 * @author jackin
 * @date 2022/5/17 18:30
 **/
public class MongodbWriter {

	public static final String TAG = MongodbV4StreamReader.class.getSimpleName();

	protected MongoClient mongoClient;
	private MongoDatabase mongoDatabase;
	private KVMap<Object> globalStateMap;
	private ConnectionString connectionString;
	private MongodbConfig mongodbConfig;

	private boolean is_cloud;

	public MongodbWriter(KVMap<Object> globalStateMap, MongodbConfig mongodbConfig, MongoClient mongoClient) {
		this.globalStateMap = globalStateMap;
		this.mongoClient = mongoClient;
		this.mongoDatabase = mongoClient.getDatabase(mongodbConfig.getDatabase());
		this.connectionString = new ConnectionString(mongodbConfig.getUri());
		this.mongodbConfig = mongodbConfig;
		this.is_cloud = AppType.init().isCloud();
	}

	/**
	 * The method invocation life circle is below,
	 * initiated ->
	 * if(needCreateTable)
	 * createTable
	 * if(needClearTable)
	 * clearTable
	 * if(needDropTable)
	 * dropTable
	 * writeRecord
	 * -> destroy -> ended
	 *
	 * @param tapRecordEvents
	 * @param writeListResultConsumer
	 */
	public void writeRecord(List<TapRecordEvent> tapRecordEvents, TapTable table, Consumer<WriteListResult<TapRecordEvent>> writeListResultConsumer) throws Throwable {
		if (CollectionUtils.isEmpty(tapRecordEvents)) {
			return;
		}
		AtomicLong inserted = new AtomicLong(0); //insert count
		AtomicLong updated = new AtomicLong(0); //update count
		AtomicLong deleted = new AtomicLong(0); //delete count

		WriteListResult<TapRecordEvent> writeListResult = writeListResult();

		MongoCollection<Document> collection = getMongoCollection(table.getId());

		Object pksCache = table.primaryKeys(true);
		if (null == pksCache) pksCache = table.primaryKeys();
		final Collection<String> pks = (Collection<String>) pksCache;

		// daas data will cache local
		if (!is_cloud && mongodbConfig.isEnableSaveDeleteData()) {
			List<TapRecordEvent> dispatchTapRecordEvents = new ArrayList<>();
			int lastRecordType = 0;
			for (TapRecordEvent tapRecordEvent : tapRecordEvents) {
				int tapRecordEventType = tapRecordEvent.getType();
				if (0 == lastRecordType) {
					lastRecordType = tapRecordEventType;
				}
				if (lastRecordType != tapRecordEventType) {
					if (TapDeleteRecordEvent.TYPE == lastRecordType) {
						MongodbLookupUtil.lookUpAndSaveDeleteMessage(dispatchTapRecordEvents, this.globalStateMap, this.connectionString, pks, collection);
					}
					if (TapDeleteRecordEvent.TYPE == tapRecordEventType) {
						bulkWrite(dispatchTapRecordEvents, table, inserted, updated, deleted, collection, pks, writeListResult);
						dispatchTapRecordEvents.clear();
					}
				}
				dispatchTapRecordEvents.add(tapRecordEvent);
				lastRecordType = tapRecordEvent.getType();
			}
			if (CollectionUtils.isNotEmpty(dispatchTapRecordEvents)) {
				if (TapDeleteRecordEvent.TYPE == dispatchTapRecordEvents.get(0).getType()) {
					MongodbLookupUtil.lookUpAndSaveDeleteMessage(dispatchTapRecordEvents, this.globalStateMap, this.connectionString, pks, collection);
				}
				bulkWrite(dispatchTapRecordEvents, table, inserted, updated, deleted, collection, pks, writeListResult);
			}
		} else {
			bulkWrite(tapRecordEvents, table, inserted, updated, deleted, collection, pks, writeListResult);
		}

		//Need to tell incremental engine the write result
		writeListResultConsumer.accept(writeListResult
				.insertedCount(inserted.get())
				.modifiedCount(updated.get())
				.removedCount(deleted.get()));
	}

	private void bulkWrite(List<TapRecordEvent> tapRecordEvents, TapTable table, AtomicLong inserted, AtomicLong updated, AtomicLong deleted, MongoCollection<Document> collection, Collection<String> pks, WriteListResult<TapRecordEvent> writeListResult) {
		removeOidIfNeed(tapRecordEvents, pks);
		BulkWriteModel bulkWriteModel = buildBulkWriteModel(tapRecordEvents, table, inserted, updated, deleted, collection, pks);

		if (bulkWriteModel.isEmpty()) {
			throw new RuntimeException("Bulk write data failed, write model list is empty, received record size: " + tapRecordEvents.size());
		}

		BulkWriteOptions bulkWriteOptions;
		AtomicReference<MongoBulkWriteException> mongoBulkWriteException = new AtomicReference<>();
		while (!bulkWriteModel.isEmpty()) {
			bulkWriteOptions = buildBulkWriteOptions(bulkWriteModel);
			try {
				List<WriteModel<Document>> writeModels = bulkWriteModel.getWriteModels();
				collection.bulkWrite(writeModels, bulkWriteOptions);
				bulkWriteModel.clearAll();
			} catch (MongoBulkWriteException e) {
				Consumer<MongoBulkWriteException> errorConsumer = mongoBulkWriteException::set;
				if (!handleBulkWriteError(e, bulkWriteModel, bulkWriteOptions, collection, errorConsumer)) {
					if (null != mongoBulkWriteException.get()) {
						throw mongoBulkWriteException.get();
					} else {
						throw e;
					}
				}
			}
		}
	}

	private void removeOidIfNeed(List<TapRecordEvent> tapRecordEvents, Collection<String> pks) {
		if (null == tapRecordEvents) {
			return;
		}
		HashSet<String> pkSet = new HashSet<>(pks);
		// remove _id in after
		for (TapRecordEvent tapRecordEvent : tapRecordEvents) {
			Object mergeInfoObj = tapRecordEvent.getInfo(MergeInfo.EVENT_INFO_KEY);
			MergeInfo mergeInfo;
			if (mergeInfoObj instanceof MergeInfo) {
				mergeInfo = (MergeInfo) mergeInfoObj;
				MergeTableProperties currentProperty = mergeInfo.getCurrentProperty();
				MergeTableProperties.MergeType mergeType = currentProperty.getMergeType();
				List<Map<String, String>> joinKeys;
				switch (mergeType) {
					case updateOrInsert:
					case updateWrite:
						joinKeys = currentProperty.getJoinKeys();
						if (CollectionUtils.isNotEmpty(joinKeys)) {
							pkSet.clear();
							pkSet.addAll(joinKeys.stream().map(jk -> jk.get("source")).collect(Collectors.toList()));
						}
						break;
					case updateIntoArray:
						joinKeys = currentProperty.getJoinKeys();
						List<String> arrayKeys = currentProperty.getArrayKeys();
						if (CollectionUtils.isNotEmpty(joinKeys)) {
							pkSet.clear();
							pkSet.addAll(joinKeys.stream().map(jk -> jk.get("source")).collect(Collectors.toList()));
						}
						if (CollectionUtils.isNotEmpty(arrayKeys)) {
							pkSet.addAll(arrayKeys);
						}
						break;
				}
				recursiveRemoveOidInMergeResults(mergeInfo.getMergeLookupResults());
			}
			if (pkSet.contains("_id")) {
				continue;
			}
			Map<String, Object> after = null;
			if (tapRecordEvent instanceof TapInsertRecordEvent) {
				after = ((TapInsertRecordEvent) tapRecordEvent).getAfter();
			} else if (tapRecordEvent instanceof TapUpdateRecordEvent) {
				after = ((TapUpdateRecordEvent) tapRecordEvent).getAfter();
			}
			if (null == after) {
				continue;
			}
			after.remove("_id");
		}
	}

	private void recursiveRemoveOidInMergeResults(List<MergeLookupResult> mergeLookupResults) {
		if (CollectionUtils.isEmpty(mergeLookupResults)) return;
		for (MergeLookupResult mergeLookupResult : mergeLookupResults) {
			Set<String> keys = new HashSet<>();
			MergeTableProperties property = mergeLookupResult.getProperty();
			MergeTableProperties.MergeType mergeType = property.getMergeType();
			List<Map<String, String>> joinKeys;
			switch (mergeType) {
				case updateOrInsert:
				case updateWrite:
					joinKeys = property.getJoinKeys();
					if (CollectionUtils.isNotEmpty(joinKeys)) {
						keys.addAll(joinKeys.stream().map(jk -> jk.get("source")).collect(Collectors.toList()));
					}
					break;
				case updateIntoArray:
					joinKeys = property.getJoinKeys();
					List<String> arrayKeys = property.getArrayKeys();
					if (CollectionUtils.isNotEmpty(joinKeys)) {
						keys.addAll(joinKeys.stream().map(jk -> jk.get("source")).collect(Collectors.toList()));
					}
					if (CollectionUtils.isNotEmpty(arrayKeys)) {
						keys.addAll(arrayKeys);
					}
					break;
			}
			Map<String, Object> data = mergeLookupResult.getData();
			if (!keys.contains("_id") && MapUtils.isNotEmpty(data)) {
				data.remove("_id");
			}
			if (CollectionUtils.isNotEmpty(mergeLookupResult.getMergeLookupResults())) {
				recursiveRemoveOidInMergeResults(mergeLookupResult.getMergeLookupResults());
			}
		}
	}

	private boolean handleBulkWriteError(
			MongoBulkWriteException originMongoBulkWriteException,
			BulkWriteModel bulkWriteModel,
			BulkWriteOptions bulkWriteOptions,
			MongoCollection<Document> collection,
			Consumer<MongoBulkWriteException> errorConsumer
	) {
		List<BulkWriteError> writeErrors = originMongoBulkWriteException.getWriteErrors();
		List<BulkWriteError> cantHandleErrors = new ArrayList<>();
		List<WriteModel<Document>> retryWriteModels = new ArrayList<>();
		for (BulkWriteError writeError : writeErrors) {
			int code = writeError.getCode();
			int index = writeError.getIndex();
			WriteModel<Document> writeModel = bulkWriteModel.getWriteModels().get(index);
			BulkWriteErrorCodeHandlerEnum bulkWriteErrorCodeHandlerEnum = BulkWriteErrorCodeHandlerEnum.fromCode(code);
			if (null != bulkWriteErrorCodeHandlerEnum && null != bulkWriteErrorCodeHandlerEnum.getBulkWriteErrorHandler()) {
				WriteModel<Document> retryWriteModel = null;
				try {
					retryWriteModel = bulkWriteErrorCodeHandlerEnum.getBulkWriteErrorHandler().handle(bulkWriteModel, writeModel, bulkWriteOptions, originMongoBulkWriteException, writeError, collection);
				} catch (Exception ignored) {
				}
				if (null != retryWriteModel) {
					retryWriteModels.add(retryWriteModel);
				} else {
					cantHandleErrors.add(writeError);
				}
			} else {
				cantHandleErrors.add(writeError);
			}
		}
		if (CollectionUtils.isNotEmpty(cantHandleErrors)) {
			// Keep errors that cannot handle
			MongoBulkWriteException mongoBulkWriteException = new MongoBulkWriteException(
					originMongoBulkWriteException.getWriteResult(),
					cantHandleErrors,
					originMongoBulkWriteException.getWriteConcernError(),
					originMongoBulkWriteException.getServerAddress(),
					originMongoBulkWriteException.getErrorLabels()
			);
			errorConsumer.accept(mongoBulkWriteException);
			return false;
		} else {
			bulkWriteModel.clearAll();
			retryWriteModels.forEach(bulkWriteModel::addAnyOpModel);
			return true;
		}
	}

	private BulkWriteModel buildBulkWriteModel(List<TapRecordEvent> tapRecordEvents, TapTable table, AtomicLong inserted, AtomicLong updated, AtomicLong deleted, MongoCollection<Document> collection, Collection<String> pks) {
		BulkWriteModel bulkWriteModel = new BulkWriteModel(pks.contains("_id"));
		for (TapRecordEvent recordEvent : tapRecordEvents) {
			if (!(recordEvent instanceof TapInsertRecordEvent)) {
				bulkWriteModel.setAllInsert(false);
			}
			if (bulkWriteModel.isAllInsert()) {
				bulkWriteModel.addOnlyInsertModel(new InsertOneModel<>(new Document(((TapInsertRecordEvent) recordEvent).getAfter())));
			}

			UpdateOptions options = new UpdateOptions().upsert(true);
			final Map<String, Object> info = recordEvent.getInfo();
			if (MapUtils.isNotEmpty(info) && info.containsKey(MergeInfo.EVENT_INFO_KEY)) {
				bulkWriteModel.setAllInsert(false);
				final List<WriteModel<Document>> mergeWriteModels = MongodbMergeOperate.merge(inserted, updated, deleted, recordEvent, table);
				if (CollectionUtils.isNotEmpty(mergeWriteModels)) {
					mergeWriteModels.forEach(bulkWriteModel::addAnyOpModel);
				}
			} else {
				WriteModel<Document> writeModel = normalWriteMode(inserted, updated, deleted, options, collection, pks, recordEvent);
				if (writeModel != null) {
					bulkWriteModel.addAnyOpModel(writeModel);
				}
			}
		}
		return bulkWriteModel;
	}

	private static BulkWriteOptions buildBulkWriteOptions(BulkWriteModel bulkWriteModel) {
		BulkWriteOptions bulkWriteOptions = new BulkWriteOptions();
		if (bulkWriteModel.isAllInsert()) {
			bulkWriteOptions.ordered(false);
		} else {
			bulkWriteOptions.ordered(true);
		}
		return bulkWriteOptions;
	}

	private WriteModel<Document> normalWriteMode(AtomicLong inserted, AtomicLong updated, AtomicLong deleted, UpdateOptions options, MongoCollection<Document> collection, Collection<String> pks, TapRecordEvent recordEvent) {
		WriteModel<Document> writeModel = null;
		if (recordEvent instanceof TapInsertRecordEvent) {
			TapInsertRecordEvent insertRecordEvent = (TapInsertRecordEvent) recordEvent;

			if (CollectionUtils.isNotEmpty(pks)) {
				final Document pkFilter = getPkFilter(pks, insertRecordEvent.getAfter());
				String operation = "$set";
				if (ConnectionOptions.DML_INSERT_POLICY_IGNORE_ON_EXISTS.equals(mongodbConfig.getInsertDmlPolicy())) {
					operation = "$setOnInsert";
				}

				MongodbUtil.removeIdIfNeed(pks, insertRecordEvent.getAfter());
				writeModel = new UpdateManyModel<>(pkFilter, new Document().append(operation, insertRecordEvent.getAfter()), options);
			} else {
				writeModel = new InsertOneModel<>(new Document(insertRecordEvent.getAfter()));
			}
			inserted.incrementAndGet();
		} else if (recordEvent instanceof TapUpdateRecordEvent && CollectionUtils.isNotEmpty(pks)) {

			TapUpdateRecordEvent updateRecordEvent = (TapUpdateRecordEvent) recordEvent;
			Map<String, Object> after = updateRecordEvent.getAfter();
			Map<String, Object> before = updateRecordEvent.getBefore();
			Map<String, Object> info = recordEvent.getInfo();
			Document pkFilter;
			Document u = new Document();
			if (info != null && info.get("$op") != null) {
				pkFilter = new Document("_id", info.get("_id"));
				u.putAll((Map<String, Object>) info.get("$op"));
				boolean isUpdate = u.keySet().stream().anyMatch(k -> k.startsWith("$"));
				if (isUpdate) {
					writeModel = new UpdateManyModel<>(pkFilter, u, options);
					options.upsert(false);
				} else {
					writeModel = new ReplaceOneModel<>(pkFilter, u, new ReplaceOptions().upsert(false));
				}
			} else {
				pkFilter = getPkFilter(pks, before != null && !before.isEmpty() ? before : after);
				if (ConnectionOptions.DML_UPDATE_POLICY_IGNORE_ON_NON_EXISTS.equals(mongodbConfig.getUpdateDmlPolicy())) {
					options.upsert(false);
				}
				MongodbUtil.removeIdIfNeed(pks, after);
				u.append("$set", after);
				if (info != null) {
					Object unset = info.get("$unset");
					if (unset != null) {
						u.append("$unset", unset);
					}
				}
				writeModel = new UpdateManyModel<>(pkFilter, u, options);
			}
			updated.incrementAndGet();
		} else if (recordEvent instanceof TapDeleteRecordEvent && CollectionUtils.isNotEmpty(pks)) {

			TapDeleteRecordEvent deleteRecordEvent = (TapDeleteRecordEvent) recordEvent;
			Map<String, Object> before = deleteRecordEvent.getBefore();
			final Document pkFilter = getPkFilter(pks, before);

			writeModel = new DeleteOneModel<>(pkFilter);
			deleted.incrementAndGet();
		}

		return writeModel;
	}

	public void onDestroy() {
		if (mongoClient != null) {
			mongoClient.close();
		}
	}

	private MongoCollection<Document> getMongoCollection(String table) {
		return mongoDatabase.getCollection(table);
	}

	private Document getPkFilter(Collection<String> pks, Map<String, Object> record) {
		Document filter = new Document();
		for (String pk : pks) {
			if (!record.containsKey(pk)) {
				throw new RuntimeException("Set filter clause failed, unique key \"" + pk + "\" not exists in data: " + record);
			}
			filter.append(pk, record.get(pk));
		}

		return filter;
	}
}
