package io.tapdata.Runnable;

import com.tapdata.constant.ConnectionUtil;
import com.tapdata.constant.ConnectorConstant;
import com.tapdata.constant.Log4jUtil;
import com.tapdata.constant.UUIDGenerator;
import com.tapdata.entity.*;
import com.tapdata.mongo.ClientMongoOperator;
import com.tapdata.validator.SchemaFactory;
import io.tapdata.TapInterface;
import io.tapdata.common.ConverterUtil;
import io.tapdata.common.TapInterfaceUtil;
import io.tapdata.entity.LoadSchemaResult;
import io.tapdata.entity.conversion.TableFieldTypesGenerator;
import io.tapdata.entity.mapping.DefaultExpressionMatchingMap;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.entity.utils.InstanceFactory;
import io.tapdata.exception.ConvertException;
import io.tapdata.pdk.core.api.ConnectionNode;
import io.tapdata.pdk.core.api.PDKIntegration;
import io.tapdata.pdk.core.monitor.PDKInvocationMonitor;
import io.tapdata.pdk.core.monitor.PDKMethod;
import io.tapdata.schema.SchemaProxy;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * @author samuel
 * @Description
 * @create 2020-08-21 16:48
 **/
public class LoadSchemaRunner implements Runnable {

	private final static String TAG = LoadSchemaRunner.class.getSimpleName();
	private final static String THREAD_NAME = "LOAD-SCHEMA-FIELDS-[%s]";
	private final static int BATCH_SIZE = 20;
	private final static String LOAD_SCHEMA_PROGRESS_WEBSOCKET_TYPE = "load_schema_progress";

	private Logger logger = LogManager.getLogger(LoadSchemaRunner.class);

	private Connections connections;
	private ClientMongoOperator clientMongoOperator;

	private Schema schema;
	private LoadSchemaProgress loadSchemaProgress;
	private String schemaVersion;
	private Long lastUpdate = System.currentTimeMillis();

	public LoadSchemaRunner(Connections connections, ClientMongoOperator clientMongoOperator, int tableCount) {
		this.connections = connections;
		this.clientMongoOperator = clientMongoOperator;
		this.schemaVersion = UUIDGenerator.uuid();
		this.loadSchemaProgress = new LoadSchemaProgress(connections.getId(), tableCount, 0);
		List<RelateDataBaseTable> tables = new ArrayList<>();
		List<TapTable> tapTables = new ArrayList<>();
		schema = new Schema(tables);
		schema.setTapTables(tapTables);
	}

	public void tableConsumer(RelateDataBaseTable table) {
		if (table == null) {
			return;
		}
		if (table.isLast()) {
			updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_FINISHED, null);
		} else {
			schema.getTables().add(table);
			loadSchemaProgress.increaLoadCount(1);
			updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_LOADING, null);
		}
	}

	public void tableConsumer(TapTable table) {
		if (table == null) {
			updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_FINISHED, null);
		} else {
			schema.getTapTables().add(table);
			loadSchemaProgress.increaLoadCount(1);
			updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_LOADING, null);
		}
	}

	@Override
	public void run() {
		if (connections == null || clientMongoOperator == null) {
			return;
		}
		Thread.currentThread().setName(String.format(THREAD_NAME, connections.getName()));

		logger.info("Starting load schema fields, connection name: {}", connections.getName());

		Update update = new Update().set(ConnectorConstant.LOAD_FIELDS, ConnectorConstant.LOAD_FIELD_STATUS_LOADING);
		updateConnections(update);

		try {
			if (SchemaFactory.canLoad(connections)) {
				if (loadSchemaProgress.getTableCount() <= 0) {
					Schema schemaOnlyTable = SchemaFactory.loadSchemaList(connections, false);
					loadSchemaProgress.setTableCount(schemaOnlyTable.getTables().size());
				}

				if (loadSchemaProgress.getTableCount() > 0) {
					SchemaFactory.loadSchemaList(connections, this::tableConsumer);
				}
				updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_FINISHED, null);
			} else {
				if (StringUtils.isBlank(connections.getPdkType())) {
					TapInterface tapInterface = TapInterfaceUtil.getTapInterface(connections.getDatabase_type(), null);
					if (tapInterface != null) {

						if (loadSchemaProgress.getTableCount() <= 0) {
							connections.setLoadSchemaField(false);
							LoadSchemaResult schemaResultTableOnly = tapInterface.loadSchema(connections);
							if (null != schemaResultTableOnly && CollectionUtils.isNotEmpty(schemaResultTableOnly.getSchema())) {
								loadSchemaProgress.setTableCount(schemaResultTableOnly.getSchema().size());
							}
						}

						if (loadSchemaProgress.getTableCount() > 0) {
							connections.setLoadSchemaField(true);
							connections.setTableConsumer(this::tableConsumer);
							LoadSchemaResult loadSchemaResult = tapInterface.loadSchema(connections);
							if (StringUtils.isNotBlank(loadSchemaResult.getErrMessage())) {
								if (loadSchemaResult.getThrowable() != null) {
									throw new RuntimeException(loadSchemaResult.getErrMessage(), loadSchemaResult.getThrowable());
								} else {
									throw new RuntimeException(loadSchemaResult.getErrMessage());
								}
							}
						}
						updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_FINISHED, null);
					}
				} else {
					long ts = System.currentTimeMillis();
					ConnectionNode connectionNode = null;
					try {
						DatabaseTypeEnum.DatabaseType databaseType = ConnectionUtil.getDatabaseType(clientMongoOperator, connections.getPdkHash());
						connectionNode = PDKIntegration.createConnectionConnectorBuilder()
								.withConnectionConfig(new DataMap() {{
									putAll(connections.getConfig());
								}})
								.withGroup(databaseType.getGroup())
								.withPdkId(databaseType.getPdkId())
								.withAssociateId(connections.getName() + "_" + ts)
								.withVersion(databaseType.getVersion())
								.build();
						PDKInvocationMonitor.invoke(connectionNode, PDKMethod.INIT, connectionNode::connectorInit, "Init PDK", TAG);
						if (loadSchemaProgress.getTableCount() <= 0) {
							loadSchemaProgress.setTableCount(getPdkTableCount(connectionNode));
						}

						if (loadSchemaProgress.getTableCount() > 0) {
							connections.setLoadSchemaField(true);
							loadPdkSchema(connections, connectionNode, this::tableConsumer);
						}
					} finally {
//            Optional.ofNullable(connectionNode).ifPresent(c -> PDKInvocationMonitor.invoke(c, PDKMethod.DESTROY, c::connectorDestroy, "Destroy PDK", TAG));
						//TODO Stop is enough here right?
						if (connectionNode != null)
							PDKInvocationMonitor.invoke(connectionNode, PDKMethod.STOP, connectionNode::connectorStop, "Stop PDK", TAG);
						PDKIntegration.releaseAssociateId(connections.getName() + "_" + ts);
					}
				}

				updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_FINISHED, null);
			}

			// After load schema, clear schema proxy
			SchemaProxy.getSchemaProxy().clear(connections.getId());

			logger.info("Finished load schema fields, connection name: {}, progress: {}/{}", connections.getName(),
					loadSchemaProgress.getLoadCount(), loadSchemaProgress.getTableCount());
		} catch (Exception e) {
			String msg = String.format("Load schema fields error, connection name: %s, message: %s", connections.getName(), e.getMessage());
			logger.error(msg + "\n  " + Log4jUtil.getStackString(e), e);
			updateSchema(ConnectorConstant.LOAD_FIELD_STATUS_ERROR, new RuntimeException(msg, e));
		}
	}

	public static int getPdkTableCount(ConnectionNode connectionNode) throws Exception {
		try {
			AtomicInteger count = new AtomicInteger();
			PDKInvocationMonitor.invoke(connectionNode, PDKMethod.TABLE_COUNT, () -> count.set(connectionNode.tableCount()), "Table Count", TAG);
			return count.get();
		} catch (Throwable throwable) {
			throw new Exception("Load pdk schema failed, message: " + throwable.getMessage(), throwable);
		}
	}

	public static void loadPdkSchema(Connections connections, ConnectionNode connectionNode, Consumer<TapTable> tableConsumer) throws Exception {
		try {
			String tableFilter = connections.getTable_filter();
			List<String> tableNameFilterList = new ArrayList<>();
			if (StringUtils.isNotBlank(tableFilter)) {
				String[] split = tableFilter.split(",");
				if (split.length > 0) {
					tableNameFilterList = Arrays.asList(split);
				}
			}
			TableFieldTypesGenerator tableFieldTypesGenerator = InstanceFactory.instance(TableFieldTypesGenerator.class);
			DefaultExpressionMatchingMap dataTypesMap = connectionNode.getConnectionContext().getSpecification().getDataTypesMap();
			List<String> finalTableNameFilterList = tableNameFilterList;
			PDKInvocationMonitor.invoke(connectionNode, PDKMethod.DISCOVER_SCHEMA,
					() -> connectionNode.getConnectorNode().discoverSchema(connectionNode.getConnectionContext(), finalTableNameFilterList, BATCH_SIZE, tables -> {
						if (CollectionUtils.isNotEmpty(tables)) {
							for (TapTable pdkTable : tables) {
								LinkedHashMap<String, TapField> nameFieldMap = pdkTable.getNameFieldMap();
								if (MapUtils.isNotEmpty(nameFieldMap)) {
									nameFieldMap.forEach((fieldName, tapField) -> {
										if (null == tapField.getTapType()) {
											tableFieldTypesGenerator.autoFill(tapField, dataTypesMap);
										}
									});
								}
								tableConsumer.accept(pdkTable);
							}
						}
					}), TAG);
			tableConsumer.accept(null);
		} catch (Throwable throwable) {
			throw new Exception("Load pdk schema failed, message: " + throwable.getMessage(), throwable);
		}
	}

	private void updateSchema(String loadFieldsStatus, Throwable error) {
		Update update = new Update();
		boolean needUpdate = false;
		if (schema != null
				&& ((CollectionUtils.isNotEmpty(schema.getTables()) && schema.getTables().size() % BATCH_SIZE == 0)
				|| (CollectionUtils.isNotEmpty(schema.getTapTables()) && schema.getTapTables().size() % BATCH_SIZE == 0))) {
			setSchemaTables(update);
			update.set("tableCount", loadSchemaProgress.getTableCount())
					.set("loadCount", loadSchemaProgress.getLoadCount());
			needUpdate = true;
		}

		if (StringUtils.equalsAny(loadFieldsStatus, ConnectorConstant.LOAD_FIELD_STATUS_FINISHED, ConnectorConstant.LOAD_FIELD_STATUS_ERROR)) {
			setSchemaTables(update);
			update.set("tableCount", loadSchemaProgress.getTableCount())
					.set("loadCount", loadSchemaProgress.getLoadCount());
			if (error != null) {
				update.set("loadFieldErrMsg", error + "\n  " + Log4jUtil.getStackString(error));
			} else {
				update.set("loadFieldErrMsg", "");
			}
			needUpdate = true;
		}

		if (needUpdate) {
			update.set(ConnectorConstant.LOAD_FIELDS, loadFieldsStatus)
					.set("schemaVersion", this.schemaVersion)
					.set("lastUpdate", this.lastUpdate)
					.set("everLoadSchema", true);
			updateConnections(update);
			schema.getTables().clear();
			schema.getTapTables().clear();
		}
	}

	private void updateConnections(Update update) {
		Query query = new Query(Criteria.where("_id").is(connections.getId()));
		if (update == null) {
			return;
		}
		clientMongoOperator.update(query, update, ConnectorConstant.CONNECTION_COLLECTION);
	}

	private void setSchemaTables(Update update) {
		if (schema == null) {
			return;
		}
		if (CollectionUtils.isNotEmpty(schema.getTables())) {
			List<RelateDataBaseTable> tables = schema.getTables();
			try {
				ConverterUtil.schemaConvert(schema.getTables(), connections.getDatabase_type());
			} catch (ConvertException e) {
				logger.error("Load schema when convert type error, connection name: {}, err msg: {}", connections.getName(), e.getMessage(), e);
			}
			tables.forEach(table -> table.setSchemaVersion(schemaVersion));
			update.set("schema.tables", tables);
		}
		if (CollectionUtils.isNotEmpty(schema.getTapTables())) {
			List<TapTable> tapTables = schema.getTapTables();
			tapTables.forEach(t -> t.setLastUpdate(lastUpdate));
			update.set("schema.tables", tapTables);
		}
	}
}
