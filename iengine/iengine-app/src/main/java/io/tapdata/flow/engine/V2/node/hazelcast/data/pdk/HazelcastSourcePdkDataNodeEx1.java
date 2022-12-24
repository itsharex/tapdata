package io.tapdata.flow.engine.V2.node.hazelcast.data.pdk;

import com.tapdata.constant.CollectionUtil;
import com.tapdata.constant.ExecutorUtil;
import com.tapdata.constant.Log4jUtil;
import com.tapdata.entity.SyncStage;
import com.tapdata.entity.TapdataCompleteSnapshotEvent;
import com.tapdata.entity.TapdataEvent;
import com.tapdata.entity.dataflow.SyncProgress;
import com.tapdata.entity.task.context.DataProcessorContext;
import com.tapdata.tm.commons.dag.Node;
import com.tapdata.tm.commons.dag.nodes.DataParentNode;
import com.tapdata.tm.commons.dag.vo.ReadPartitionOptions;
import com.tapdata.tm.commons.task.dto.TaskDto;
import io.tapdata.aspect.*;
import io.tapdata.aspect.utils.AspectUtils;
import io.tapdata.async.master.*;
import io.tapdata.entity.event.TapEvent;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.utils.InstanceFactory;
import io.tapdata.flow.engine.V2.exception.node.NodeException;
import io.tapdata.flow.engine.V2.progress.SnapshotProgressManager;
import io.tapdata.flow.engine.V2.sharecdc.ReaderType;
import io.tapdata.flow.engine.V2.sharecdc.ShareCdcReader;
import io.tapdata.flow.engine.V2.sharecdc.ShareCdcTaskContext;
import io.tapdata.flow.engine.V2.sharecdc.ShareCdcTaskPdkContext;
import io.tapdata.flow.engine.V2.sharecdc.exception.ShareCdcUnsupportedException;
import io.tapdata.flow.engine.V2.sharecdc.impl.ShareCdcFactory;
import io.tapdata.milestone.MilestoneStage;
import io.tapdata.milestone.MilestoneStatus;
import io.tapdata.pdk.apis.consumer.StreamReadConsumer;
import io.tapdata.pdk.apis.functions.PDKMethod;
import io.tapdata.pdk.apis.functions.connector.source.*;
import io.tapdata.pdk.core.api.ConnectorNode;
import io.tapdata.pdk.core.entity.params.PDKMethodInvoker;
import io.tapdata.pdk.core.monitor.PDKInvocationMonitor;
import io.tapdata.pdk.core.utils.CommonUtils;
import io.tapdata.pdk.core.utils.LoggerUtils;
import io.tapdata.schema.TapTableMap;
import lombok.SneakyThrows;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static io.tapdata.entity.simplify.TapSimplify.sleep;

/**
 * @author jackin
 * @date 2022/2/22 2:33 PM
 **/
public class HazelcastSourcePdkDataNodeEx1 extends HazelcastSourcePdkBaseNode {
	private static final String TAG = HazelcastSourcePdkDataNodeEx1.class.getSimpleName();
	private final Logger logger = LogManager.getLogger(HazelcastSourcePdkDataNodeEx1.class);

	private static final int ASYNCLY_COUNT_SNAPSHOT_ROW_SIZE_TABLE_THRESHOLD = 100;

	private ShareCdcReader shareCdcReader;

	private final SourceStateAspect sourceStateAspect;
	private Map<String, Long> snapshotRowSizeMap;
	private ExecutorService snapshotRowSizeThreadPool;
	private final AsyncMaster asyncMaster;
	private AsyncQueueWorker initialSyncWorker;
	private AsyncQueueWorker streamReadWorker;
	private AsyncParallelWorker partitionsReader;

	public HazelcastSourcePdkDataNodeEx1(DataProcessorContext dataProcessorContext) {
		super(dataProcessorContext);
		sourceStateAspect = new SourceStateAspect().dataProcessorContext(dataProcessorContext);
		asyncMaster = InstanceFactory.instance(AsyncMaster.class);
	}

	@Override
	protected void doInit(@NotNull Context context) throws Exception {
		try {
			super.doInit(context);
			// MILESTONE-INIT_CONNECTOR-FINISH
			TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.INIT_CONNECTOR, MilestoneStatus.FINISH);
//			MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.INIT_CONNECTOR, MilestoneStatus.FINISH);
		} catch (Throwable e) {
			// MILESTONE-INIT_CONNECTOR-ERROR
			TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.INIT_CONNECTOR, MilestoneStatus.ERROR, logger);
//			MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.INIT_CONNECTOR, MilestoneStatus.ERROR, e.getMessage() + "\n" + Log4jUtil.getStackString(e));
			//Notify error for task.
			throw errorHandle(e, "init failed");
		}
	}

	@Override
	public void startSourceRunner() {
		startAsyncJobs();
	}
	private JobContext handleBatchRead(JobContext jobContext) {
		ReadPartitionOptions readPartitionOptions = null;
		Node<?> node = dataProcessorContext.getNode();
		if(node instanceof DataParentNode) {
			DataParentNode<?> dataParentNode = (DataParentNode<?>) node;
			readPartitionOptions = dataParentNode.getReadPartitionOptions();
		}
		if(readPartitionOptions == null)
			readPartitionOptions = new ReadPartitionOptions();

		syncProgress.setSyncStage(SyncStage.INITIAL_SYNC.name());
		snapshotProgressManager = new SnapshotProgressManager(dataProcessorContext.getTaskDto(), clientMongoOperator,
				getConnectorNode(), dataProcessorContext.getTapTableMap());

		PDKSourceContext pdkSourceContext = jobContext.getContext(PDKSourceContext.class);

		if(readPartitionOptions.getSplitType() == ReadPartitionOptions.SPLIT_TYPE_BY_COUNT)
			doCount(pdkSourceContext.getPendingInitialSyncTables());

		GetReadPartitionsFunction getReadPartitionsFunction = getConnectorNode().getConnectorFunctions().getGetReadPartitionsFunction();
		if (getReadPartitionsFunction != null) {
			if (sourceRunnerFirstTime.get()) {
				AspectUtils.executeAspect(sourceStateAspect.state(SourceStateAspect.STATE_INITIAL_SYNC_START));
				TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.READ_SNAPSHOT, MilestoneStatus.RUNNING);
			}
//			MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.READ_SNAPSHOT, MilestoneStatus.RUNNING);

			ReadPartitionOptions finalReadPartitionOptions = readPartitionOptions;
			jobContext.foreach(pdkSourceContext.getPendingInitialSyncTables(), table -> {
				if(finalReadPartitionOptions.getSplitType() == ReadPartitionOptions.SPLIT_TYPE_BY_COUNT) {
					//Wait table count finish to continue
					jobContext.foreach(Integer.MAX_VALUE, integer -> {
						sleep(500);
						return null == snapshotRowSizeMap || !snapshotRowSizeMap.containsKey(table);
					});
				}

				if (this.removeTables != null && this.removeTables.contains(table)) {
//					logger.info("Table " + table + " is detected that it has been removed, the snapshot read will be skipped");
					obsLogger.info("Table " + table + " is detected that it has been removed, the snapshot read will be skipped");
					this.removeTables.remove(table);
					return null;
				}
				TapTable tapTable = dataProcessorContext.getTapTableMap().get(table);
				Object tableOffset = ((Map<String, Object>) syncProgress.getBatchOffsetObj()).get(tapTable.getId());
//				logger.info("Starting batch read, table name: " + tapTable.getId() + ", offset: " + tableOffset);
				obsLogger.info("Starting batch read, table name: " + tapTable.getId() + ", offset: " + tableOffset);
				int eventBatchSize = 100;

				executeDataFuncAspect(
						GetReadPartitionsFuncAspect.class, () -> new GetReadPartitionsFuncAspect()
								.connectorContext(getConnectorNode().getConnectorContext())
								.dataProcessorContext(this.getDataProcessorContext())
								.splitType(finalReadPartitionOptions.getSplitType())
								.maxRecordInPartition(finalReadPartitionOptions.getMaxRecordInPartition())
								.start()
								.table(tapTable),
						getReadPartitionsFuncAspect -> PDKInvocationMonitor.invoke(
								getConnectorNode(), PDKMethod.SOURCE_GET_READ_PARTITIONS,
								createPdkMethodInvoker().runnable(() ->
										getReadPartitionsFunction.getReadPartitions(
												getConnectorNode().getConnectorContext(),
												tapTable,
												finalReadPartitionOptions.getMaxRecordInPartition(),
												null,
												finalReadPartitionOptions.getSplitType(),
												(readPartition) -> {
													if (getReadPartitionsFuncAspect != null)
														AspectUtils.accept(getReadPartitionsFuncAspect.state(GetReadPartitionsFuncAspect.STATE_READ_COMPLETE).getReadCompleteConsumers(), readPartition);

													partitionsReader.job(readPartition.getId(),
															JobContext.create(null).context(ReadPartitionContext.create().pdkSourceContext(pdkSourceContext).readPartition(readPartition)),
															asyncQueueWorker -> asyncQueueWorker.
																	job("Start caching stream data for partition " + readPartition, this::handleStartCachingStreamData).
																	job("Read partition " + readPartition, this::handleReadPartition).
																	job("Start sending data from partition " + readPartition, this::handleSendingDataFromPartition).
																	job("Finished partition " + readPartition, this::handleFinishedPartition));
												}))
						));

				return null;
			});
		} else {
			doSnapshot(pdkSourceContext.getPendingInitialSyncTables());
		}
		return null;
	}
	private JobContext handleFinishedPartition(JobContext jobContext) {
		return null;
	}

	private JobContext handleSendingDataFromPartition(JobContext jobContext) {
		return null;
	}

	private JobContext handleStartCachingStreamData(JobContext jobContext) {
		ReadPartitionContext readPartitionContext = jobContext.getContext(ReadPartitionContext.class);

		return null;
	}

	private JobContext handleReadPartition(JobContext jobContext) {
		return null;
	}


	public AsyncJobChain initialSyncJobChain() {
		return asyncMaster.createAsyncJobChain().job("needInitialSync", jobContext -> {
			PDKSourceContext pdkSourceContext = jobContext.getContext(PDKSourceContext.class);
			Log4jUtil.setThreadContext(dataProcessorContext.getTaskDto());

			if(pdkSourceContext.isNeedInitialSync()) {
				return jobContext.jumpToId("batchRead");
			} else {
				return jobContext;
			}
		}).job("batchRead", this::handleBatchRead, true);
//		return asyncMaster.createAsyncJobChain().job("need2InitialSync", jobContext -> {
//			boolean bool =  need2InitialSync(syncProgress);
//			if(bool) {
//				return JobContext.create(null);
//			} else {
//				return JobContext.create(null).jumpToId("needCDC");
//			}
//		}).externalJob("getReadPartitions", jobContext -> {
//			TapTableMap<String, TapTable> tapTableMap = dataProcessorContext.getTapTableMap();
//			doSnapshot(new ArrayList<>(tapTableMap.keySet()));
////			jobChain.remove()
//			return JobContext.create(null);
//		}).externalJob("batchReadMore", jobContext -> {
//			if (CollectionUtils.isNotEmpty(newTables)) {
//				doSnapshot(newTables);
//			}
//			return null;
//		}).job("needCDC", jobContext -> {
//			boolean bool = need2CDC();
//			if(bool) {
//				return JobContext.create(null).jumpToId("streamRead");
//			} else {
//				return JobContext.create(null);
//			}
//		}).externalJob("streamRead", jobContext -> {
//			return null;
//		}, true);
	}
	public AsyncJobChain streamJobChain() {
		return asyncMaster.createAsyncJobChain().job("needCDC", jobContext -> {
			PDKSourceContext pdkSourceContext = jobContext.getContext(PDKSourceContext.class);
			Log4jUtil.setThreadContext(dataProcessorContext.getTaskDto());

			if(pdkSourceContext.isNeedCDC()) {
				return jobContext.jumpToId("streamRead");
			}
			return jobContext;
		}).job("streamRead", this::handleStreamRead, true);
	}
	private JobContext handleStreamRead(JobContext jobContext) {
		try {
			AspectUtils.executeAspect(sourceStateAspect.state(SourceStateAspect.STATE_CDC_START));
//			doCdc();

//			TapdataStartCdcEvent tapdataStartCdcEvent = new TapdataStartCdcEvent();
//			tapdataStartCdcEvent.setSyncStage(SyncStage.CDC);
//			enqueue(tapdataStartCdcEvent);
//			syncProgress.setSyncStage(SyncStage.CDC.name());
			// MILESTONE-READ_CDC_EVENT-RUNNING
			TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.RUNNING);
//		MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.RUNNING);
			Node<?> node = dataProcessorContext.getNode();
			if (node.isLogCollectorNode()) {
				// Mining tasks force traditional increments
				doNormalCDC(jobContext);
			} else {
				try {
					// Try to start with share cdc
					doShareCdc();
				} catch (ShareCdcUnsupportedException e) {
					if (e.isContinueWithNormalCdc()) {
						// If share cdc is unavailable, and continue with normal cdc is true
						logger.info("Share cdc unusable, will use normal cdc mode, reason: " + e.getMessage());
						obsLogger.info("Share cdc unusable, will use normal cdc mode, reason: " + e.getMessage());
						doNormalCDC(jobContext);
					} else {
						throw new NodeException("Read share cdc log failed: " + e.getMessage(), e).context(getProcessorBaseContext());
					}
				} catch (Exception e) {
					throw new NodeException("Read share cdc log failed: " + e.getMessage(), e).context(getProcessorBaseContext());
				}
			}
		} catch (Throwable e) {
			// MILESTONE-READ_CDC_EVENT-ERROR
			TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.ERROR);
//					MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.ERROR, e.getMessage() + "\n" + Log4jUtil.getStackString(e));
			throw e;
		} finally {
			AspectUtils.executeAspect(sourceStateAspect.state(SourceStateAspect.STATE_CDC_COMPLETED));
		}
		return null;
	}
	private void handleWorkerError(String id, AsyncJob asyncJob, Throwable throwable) {
		Throwable throwableWrapper = throwable;
		if (!(throwableWrapper instanceof NodeException)) {
			throwableWrapper = new NodeException(throwableWrapper).context(getProcessorBaseContext());
		}
		//noinspection ThrowableNotThrown
		errorHandle(throwableWrapper, throwable.getMessage());
	}
	public void startAsyncJobs() {
		TapTableMap<String, TapTable> tapTableMap = dataProcessorContext.getTapTableMap();
		PDKSourceContext sourceContext = PDKSourceContext.create()
				.sourcePdkDataNode(this)
				.pendingInitialSyncTables(need2InitialSync(syncProgress) ? new ArrayList<>(tapTableMap.keySet()) : null)
				.needCDC(need2CDC());
		partitionsReader = asyncMaster.createAsyncParallelWorker("PartitionReader " + getNode().getId(), 8);
		initialSyncWorker = asyncMaster.createAsyncQueueWorker("InitialSync " + getNode().getId())
				.job(initialSyncJobChain()).start(JobContext.create(null).context(sourceContext)).setAsyncJobErrorListener(this::handleWorkerError);

		streamReadWorker = asyncMaster.createAsyncQueueWorker("StreamRead " + getNode().getId())
				.job(streamJobChain()).start(JobContext.create(null).context(sourceContext)).setAsyncJobErrorListener(this::handleWorkerError);

//		AsyncQueueWorker loadMoreTablesWorker = asyncMaster.createAsyncQueueWorker("loadMoreTables");
//		loadMoreTablesWorker.job("loadTables", jobContext -> {
//			return null;
//		}).job("loadSchema", jobContext -> {
////			asyncQueueWorker.cancelAll()
//			return null;
//		});
//		loadMoreTablesWorker.start(JobContext.create(null), 60000, 60000);
	}

	@SneakyThrows
	private void doSnapshot(List<String> tableList) {
		syncProgress.setSyncStage(SyncStage.INITIAL_SYNC.name());
		snapshotProgressManager = new SnapshotProgressManager(dataProcessorContext.getTaskDto(), clientMongoOperator,
				getConnectorNode(), dataProcessorContext.getTapTableMap());
//		snapshotProgressManager.startStatsSnapshotEdgeProgress(dataProcessorContext.getNode());

		// count the data size of the tables;
		doCount(tableList);

		BatchReadFunction batchReadFunction = getConnectorNode().getConnectorFunctions().getBatchReadFunction();
		if (batchReadFunction != null) {
			// MILESTONE-READ_SNAPSHOT-RUNNING
			if (sourceRunnerFirstTime.get()) {
				AspectUtils.executeAspect(sourceStateAspect.state(SourceStateAspect.STATE_INITIAL_SYNC_START));
				TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.READ_SNAPSHOT, MilestoneStatus.RUNNING);
			}
//			MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.READ_SNAPSHOT, MilestoneStatus.RUNNING);
			try {
				while (isRunning()) {
					for (String tableName : tableList) {
						// wait until we count the table
						while (isRunning() && (null == snapshotRowSizeMap || !snapshotRowSizeMap.containsKey(tableName))) {
							try {
								TimeUnit.MILLISECONDS.sleep(500);
							} catch (InterruptedException ignored) {
							}
						}
						try {
							while (isRunning()) {
								try {
									if (sourceRunnerLock.tryLock(1L, TimeUnit.SECONDS)) {
										break;
									}
								} catch (InterruptedException e) {
									break;
								}
							}
							if (!isRunning()) {
								break;
							}
							if (this.removeTables != null && this.removeTables.contains(tableName)) {
								logger.info("Table " + tableName + " is detected that it has been removed, the snapshot read will be skipped");
								obsLogger.info("Table " + tableName + " is detected that it has been removed, the snapshot read will be skipped");
								this.removeTables.remove(tableName);
								continue;
							}
							TapTable tapTable = dataProcessorContext.getTapTableMap().get(tableName);
							Object tableOffset = ((Map<String, Object>) syncProgress.getBatchOffsetObj()).get(tapTable.getId());
							logger.info("Starting batch read, table name: " + tapTable.getId() + ", offset: " + tableOffset);
							obsLogger.info("Starting batch read, table name: " + tapTable.getId() + ", offset: " + tableOffset);
							int eventBatchSize = 100;

							executeDataFuncAspect(
									BatchReadFuncAspect.class, () -> new BatchReadFuncAspect()
											.eventBatchSize(eventBatchSize)
											.connectorContext(getConnectorNode().getConnectorContext())
											.offsetState(tableOffset)
											.dataProcessorContext(this.getDataProcessorContext())
											.start()
											.table(tapTable),
									batchReadFuncAspect -> PDKInvocationMonitor.invoke(
											getConnectorNode(), PDKMethod.SOURCE_BATCH_READ,
											createPdkMethodInvoker().runnable(() -> batchReadFunction.batchRead(getConnectorNode().getConnectorContext(), tapTable, tableOffset, eventBatchSize, (events, offsetObject) -> {
														if (events != null && !events.isEmpty()) {
															events.forEach(event -> {
																if (null == event.getTime()) {
																	throw new NodeException("Invalid TapEvent, `TapEvent.time` should be NonNUll").context(getProcessorBaseContext()).event(event);
																}
																event.addInfo("eventId", UUID.randomUUID().toString());
															});

															if (batchReadFuncAspect != null)
																AspectUtils.accept(batchReadFuncAspect.state(BatchReadFuncAspect.STATE_READ_COMPLETE).getReadCompleteConsumers(), events);

															if (logger.isDebugEnabled()) {
																logger.debug("Batch read {} of events, {}", events.size(), LoggerUtils.sourceNodeMessage(getConnectorNode()));
															}
															((Map<String, Object>) syncProgress.getBatchOffsetObj()).put(tapTable.getId(), offsetObject);
															List<TapdataEvent> tapdataEvents = wrapTapdataEvent(events);

															if (batchReadFuncAspect != null)
																AspectUtils.accept(batchReadFuncAspect.state(BatchReadFuncAspect.STATE_PROCESS_COMPLETE).getProcessCompleteConsumers(), tapdataEvents);

															if (CollectionUtil.isNotEmpty(tapdataEvents)) {
																tapdataEvents.forEach(this::enqueue);

																if (batchReadFuncAspect != null)
																	AspectUtils.accept(batchReadFuncAspect.state(BatchReadFuncAspect.STATE_ENQUEUED).getEnqueuedConsumers(), tapdataEvents);
															}
														}
													})
											)
									));
						} catch (Throwable throwable) {
							Throwable throwableWrapper = throwable;
							if (!(throwableWrapper instanceof NodeException)) {
								throwableWrapper = new NodeException(throwableWrapper).context(getProcessorBaseContext());
							}
							throw throwableWrapper;
						} finally {
							try {
								sourceRunnerLock.unlock();
							} catch (Exception ignored) {
							}
						}
					}
					try {
						while (isRunning()) {
							try {
								if (sourceRunnerLock.tryLock(1L, TimeUnit.SECONDS)) {
									break;
								}
							} catch (InterruptedException e) {
								break;
							}
						}
						if (CollectionUtils.isNotEmpty(newTables)) {
							tableList.clear();
							tableList.addAll(newTables);
							doCount(tableList);
							newTables.clear();
						} else {
							this.endSnapshotLoop.set(true);
							break;
						}
					} finally {
						try {
							sourceRunnerLock.unlock();
						} catch (Exception ignored) {
						}
					}
				}
			} finally {
				if (isRunning()) {
					// MILESTONE-READ_SNAPSHOT-FINISH
					TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.READ_SNAPSHOT, MilestoneStatus.FINISH);
//					MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.READ_SNAPSHOT, MilestoneStatus.FINISH);
					enqueue(new TapdataCompleteSnapshotEvent());
					AspectUtils.executeAspect(sourceStateAspect.state(SourceStateAspect.STATE_INITIAL_SYNC_COMPLETED));
				}
			}
		} else {
			throw new NodeException("PDK node does not support batch read: " + dataProcessorContext.getDatabaseType())
					.context(getProcessorBaseContext());
		}
	}

	@SneakyThrows
	private void doCount(List<String> tableList) {
		BatchCountFunction batchCountFunction = getConnectorNode().getConnectorFunctions().getBatchCountFunction();
		if (null == batchCountFunction) {
			setDefaultRowSizeMap();
			logger.warn("PDK node does not support table batch count: " + dataProcessorContext.getDatabaseType());
			obsLogger.warn("PDK node does not support table batch count: " + dataProcessorContext.getDatabaseType());
			return;
		}

		if (dataProcessorContext.getTapTableMap().keySet().size() > ASYNCLY_COUNT_SNAPSHOT_ROW_SIZE_TABLE_THRESHOLD) {
			logger.info("Start to asynchronously count the size of rows for the source table(s)");
			AtomicReference<TaskDto> task = new AtomicReference<>(dataProcessorContext.getTaskDto());
			AtomicReference<Node<?>> node = new AtomicReference<>(dataProcessorContext.getNode());
			snapshotRowSizeThreadPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, new SynchronousQueue<>());
			CompletableFuture.runAsync(() -> {
						String name = String.format("Snapshot-Row-Size-Query-Thread-%s(%s)-%s(%s)",
								task.get().getName(), task.get().getId().toHexString(), node.get().getName(), node.get().getId());
						Thread.currentThread().setName(name);
						Log4jUtil.setThreadContext(task.get());

						doCountSynchronously(batchCountFunction, tableList);
					}, snapshotRowSizeThreadPool)
					.whenComplete((v, e) -> {
						if (null != e) {
							logger.warn("Query snapshot row size failed: " + e.getMessage() + "\n" + Log4jUtil.getStackString(e));
							obsLogger.warn("Query snapshot row size failed: " + e.getMessage() + "\n" + Log4jUtil.getStackString(e));
						} else {
							logger.info("Query snapshot row size completed: " + node.get().getName() + "(" + node.get().getId() + ")");
							obsLogger.info("Query snapshot row size completed: " + node.get().getName() + "(" + node.get().getId() + ")");
						}
						ExecutorUtil.shutdown(this.snapshotRowSizeThreadPool, 10L, TimeUnit.SECONDS);
					});
		} else {
			doCountSynchronously(batchCountFunction, tableList);
		}
	}

	@SneakyThrows
	private void doCountSynchronously(BatchCountFunction batchCountFunction, List<String> tableList) {
		if (null == batchCountFunction) {
			setDefaultRowSizeMap();
			logger.warn("PDK node does not support table batch count: " + dataProcessorContext.getDatabaseType());
			obsLogger.warn("PDK node does not support table batch count: " + dataProcessorContext.getDatabaseType());
			return;
		}

		for (String tableName : tableList) {
			if (!isRunning()) {
				return;
			}

			TapTable table = dataProcessorContext.getTapTableMap().get(tableName);
			executeDataFuncAspect(TableCountFuncAspect.class, () -> new TableCountFuncAspect()
							.dataProcessorContext(this.getDataProcessorContext())
							.start(),
					tableCountFuncAspect -> PDKInvocationMonitor.invoke(getConnectorNode(), PDKMethod.SOURCE_BATCH_COUNT,
							createPdkMethodInvoker().runnable(
									() -> {
										try {
											long count = batchCountFunction.count(getConnectorNode().getConnectorContext(), table);

											if (null == snapshotRowSizeMap) {
												snapshotRowSizeMap = new HashMap<>();
											}
											snapshotRowSizeMap.putIfAbsent(tableName, count);

											if (null != tableCountFuncAspect) {
												AspectUtils.accept(tableCountFuncAspect.state(TableCountFuncAspect.STATE_COUNTING).getTableCountConsumerList(), table.getName(), count);
											}
										} catch (Exception e) {
											throw new NodeException("Count " + table.getId() + " failed: " + e.getMessage(), e)
													.context(getProcessorBaseContext());
										}
									}
							)
					));
		}
	}

//	@SneakyThrows
//	private void doCdc() {
//		if (!isRunning()) {
//			return;
//		}
//		this.endSnapshotLoop.set(true);
//		if (null == syncProgress.getStreamOffsetObj()) {
//			throw new NodeException("Starting stream read failed, errors: start point offset is null").context(getProcessorBaseContext());
//		} else {
//			TapdataStartCdcEvent tapdataStartCdcEvent = new TapdataStartCdcEvent();
//			tapdataStartCdcEvent.setSyncStage(SyncStage.CDC);
//			enqueue(tapdataStartCdcEvent);
//		}
//		// MILESTONE-READ_CDC_EVENT-RUNNING
//		TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.RUNNING);
//		MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.RUNNING);
//		syncProgress.setSyncStage(SyncStage.CDC.name());
//		Node<?> node = dataProcessorContext.getNode();
//		if (node.isLogCollectorNode()) {
//			// Mining tasks force traditional increments
//			doNormalCDC();
//		} else {
//			try {
//				// Try to start with share cdc
//				doShareCdc();
//			} catch (ShareCdcUnsupportedException e) {
//				if (e.isContinueWithNormalCdc()) {
//					// If share cdc is unavailable, and continue with normal cdc is true
//					logger.info("Share cdc unusable, will use normal cdc mode, reason: " + e.getMessage());
//					obsLogger.info("Share cdc unusable, will use normal cdc mode, reason: " + e.getMessage());
//					doNormalCDC();
//				} else {
//					throw new NodeException("Read share cdc log failed: " + e.getMessage(), e).context(getProcessorBaseContext());
//				}
//			} catch (Exception e) {
//				throw new NodeException("Read share cdc log failed: " + e.getMessage(), e).context(getProcessorBaseContext());
//			}
//		}
//	}

	@SneakyThrows
	private void doNormalCDC(JobContext jobContext) {
		if (!isRunning()) {
			return;
		}
		TapTableMap<String, TapTable> tapTableMap = dataProcessorContext.getTapTableMap();
		ConnectorNode connectorNode = getConnectorNode();
		if (connectorNode == null) {
			logger.warn("Failed to get source node");
			return;
		}
		RawDataCallbackFilterFunction rawDataCallbackFilterFunction = connectorNode.getConnectorFunctions().getRawDataCallbackFilterFunction();
		RawDataCallbackFilterFunctionV2 rawDataCallbackFilterFunctionV2 = connectorNode.getConnectorFunctions().getRawDataCallbackFilterFunctionV2();
//		if(rawDataCallbackFilterFunctionV2 != null) {
//			rawDataCallbackFilterFunction = null;
//		}
		StreamReadFunction streamReadFunction = connectorNode.getConnectorFunctions().getStreamReadFunction();
		if (streamReadFunction != null || rawDataCallbackFilterFunction != null || rawDataCallbackFilterFunctionV2 != null) {
			logger.info("Starting stream read, table list: " + tapTableMap.keySet() + ", offset: " + syncProgress.getStreamOffsetObj());
			List<String> tables = new ArrayList<>(tapTableMap.keySet());
			cdcDelayCalculation.addHeartbeatTable(tables);
			int batchSize = dataProcessorContext.getTaskDto().getReadBatchSize();
			String streamReadFunctionName = null;
			if (rawDataCallbackFilterFunctionV2 != null)
				streamReadFunctionName = rawDataCallbackFilterFunctionV2.getClass().getSimpleName();
			if (rawDataCallbackFilterFunction != null && streamReadFunctionName == null)
				streamReadFunctionName = rawDataCallbackFilterFunction.getClass().getSimpleName();
			if (streamReadFunctionName == null)
				streamReadFunctionName = streamReadFunction.getClass().getSimpleName();
			String finalStreamReadFunctionName = streamReadFunctionName;
			PDKMethodInvoker pdkMethodInvoker = createPdkMethodInvoker();
			executeDataFuncAspect(StreamReadFuncAspect.class, () -> new StreamReadFuncAspect()
							.connectorContext(getConnectorNode().getConnectorContext())
							.dataProcessorContext(getDataProcessorContext())
							.streamReadFunction(finalStreamReadFunctionName)
							.tables(tables)
							.eventBatchSize(batchSize)
							.offsetState(syncProgress.getStreamOffsetObj())
							.start(),
					streamReadFuncAspect -> PDKInvocationMonitor.invoke(getConnectorNode(), PDKMethod.SOURCE_STREAM_READ,
							pdkMethodInvoker.runnable(
									() -> {
										this.streamReadFuncAspect = streamReadFuncAspect;
										StreamReadConsumer streamReadConsumer = StreamReadConsumer.create(this::handleStreamEventsReceived).stateListener((oldState, newState) -> {
											if (StreamReadConsumer.STATE_STREAM_READ_ENDED != newState) {
												PDKInvocationMonitor.invokerRetrySetter(pdkMethodInvoker);
											}
											if (null != newState && StreamReadConsumer.STATE_STREAM_READ_STARTED == newState) {
												// MILESTONE-READ_CDC_EVENT-FINISH
												if (streamReadFuncAspect != null)
													executeAspect(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_STREAM_STARTED).streamStartedTime(System.currentTimeMillis()));
												TaskMilestoneFuncAspect.execute(dataProcessorContext, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.FINISH);
//												MilestoneUtil.updateMilestone(milestoneService, MilestoneStage.READ_CDC_EVENT, MilestoneStatus.FINISH);
												logger.info("Connector start stream read succeed: {}", connectorNode);
												obsLogger.info("Connector start stream read succeed: {}", connectorNode);
												partitionsReader.start();
											}
										});

										if ((rawDataCallbackFilterFunction != null || rawDataCallbackFilterFunctionV2 != null) && streamReadFuncAspect != null) {
											executeAspect(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_CALLBACK_RAW_DATA).streamReadConsumer(streamReadConsumer));
											while (isRunning()) {
												if (!streamReadFuncAspect.waitRawData()) {
													break;
												}
											}
											if (streamReadFuncAspect.getErrorDuringWait() != null) {
												throw streamReadFuncAspect.getErrorDuringWait();
											}
										} else {
											if (streamReadFunction != null) {
												streamReadFunction.streamRead(getConnectorNode().getConnectorContext(), tables,
														syncProgress.getStreamOffsetObj(), batchSize, streamReadConsumer);
											}
										}
									}
							)
					));
		} else {
			throw new NodeException("PDK node does not support stream read: " + dataProcessorContext.getDatabaseType()).context(getProcessorBaseContext());
		}
	}

	private void handleStreamEventsReceived(List<TapEvent> events, Object offsetObj) {
		try {
//			while (isRunning()) {
//				try {
//					if (sourceRunnerLock.tryLock(1L, TimeUnit.SECONDS)) {
//						break;
//					}
//				} catch (InterruptedException e) {
//					break;
//				}
//			}
			if (events != null && !events.isEmpty()) {
				events.forEach(event -> {
					if (null == event.getTime()) {
						throw new NodeException("Invalid TapEvent, `TapEvent.time` should be NonNUll").context(getProcessorBaseContext()).event(event);
					}
					event.addInfo("eventId", UUID.randomUUID().toString());
				});

				if (streamReadFuncAspect != null) {
					AspectUtils.accept(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_STREAMING_READ_COMPLETED).getStreamingReadCompleteConsumers(), events);
				}

				List<TapdataEvent> tapdataEvents = wrapTapdataEvent(events, SyncStage.CDC, offsetObj);
				if (logger.isDebugEnabled()) {
					logger.debug("Stream read {} of events, {}", events.size(), LoggerUtils.sourceNodeMessage(getConnectorNode()));
				}

				if (streamReadFuncAspect != null)
					AspectUtils.accept(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_STREAMING_PROCESS_COMPLETED).getStreamingProcessCompleteConsumers(), tapdataEvents);

				if (CollectionUtils.isNotEmpty(tapdataEvents)) {
					tapdataEvents.forEach(this::enqueue);
					syncProgress.setStreamOffsetObj(offsetObj);
					if (streamReadFuncAspect != null)
						AspectUtils.accept(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_STREAMING_ENQUEUED).getStreamingEnqueuedConsumers(), tapdataEvents);
				}
			}
		} catch (Throwable throwable) {
			errorHandle(throwable, "Error processing incremental data, error: " + throwable.getMessage());
		} /*finally {
			try {
				sourceRunnerLock.unlock();
			} catch (Exception ignored) {
			}
		}*/
	}

	private void doShareCdc() throws Exception {
		if (!isRunning()) {
			return;
		}
		cdcDelayCalculation.addHeartbeatTable(new ArrayList<>(dataProcessorContext.getTapTableMap().keySet()));
		ShareCdcTaskContext shareCdcTaskContext = new ShareCdcTaskPdkContext(getCdcStartTs(), processorBaseContext.getConfigurationCenter(),
				dataProcessorContext.getTaskDto(), dataProcessorContext.getNode(), dataProcessorContext.getSourceConn(), getConnectorNode());
		TapTableMap<String, TapTable> tapTableMap = dataProcessorContext.getTapTableMap();
		List<String> tables = new ArrayList<>(tapTableMap.keySet());
		// Init share cdc reader, if unavailable, will throw ShareCdcUnsupportedException
		this.shareCdcReader = ShareCdcFactory.shareCdcReader(ReaderType.PDK_TASK_HAZELCAST, shareCdcTaskContext);
		logger.info("Starting incremental sync, read from share log storage...");
		obsLogger.info("Starting incremental sync, read from share log storage...");
		// Start listen message entity from share storage log
		executeDataFuncAspect(StreamReadFuncAspect.class,
				() -> new StreamReadFuncAspect()
						.dataProcessorContext(getDataProcessorContext())
						.tables(tables)
						.eventBatchSize(1)
						.offsetState(syncProgress.getStreamOffsetObj())
						.start(),
				streamReadFuncAspect -> this.shareCdcReader.listen((event, offsetObj) -> {
					if (streamReadFuncAspect != null) {
						AspectUtils.accept(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_STREAMING_READ_COMPLETED).getStreamingReadCompleteConsumers(), Collections.singletonList(event));
					}
					TapdataEvent tapdataEvent = wrapTapdataEvent(event, SyncStage.CDC, offsetObj, true);
					if (null == tapdataEvent) {
						return;
					}
					List<TapdataEvent> tapdataEvents = Collections.singletonList(tapdataEvent);
					if (streamReadFuncAspect != null)
						AspectUtils.accept(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_STREAMING_PROCESS_COMPLETED).getStreamingProcessCompleteConsumers(), tapdataEvents);
					tapdataEvent.setType(SyncProgress.Type.SHARE_CDC);
					enqueue(tapdataEvent);
					if (streamReadFuncAspect != null)
						AspectUtils.accept(streamReadFuncAspect.state(StreamReadFuncAspect.STATE_STREAMING_ENQUEUED).getStreamingEnqueuedConsumers(), tapdataEvents);
				}));
	}

	private Long getCdcStartTs() {
		Long cdcStartTs;
		try {
			if (null != this.syncProgress && null != this.syncProgress.getEventTime() && this.syncProgress.getEventTime().compareTo(0L) > 0) {
				cdcStartTs = this.syncProgress.getEventTime();
			} else {
				cdcStartTs = initialFirstStartTime;
			}
		} catch (Exception e) {
			throw new NodeException("Get cdc start ts failed; Error: " + e.getMessage(), e).context(getProcessorBaseContext());
		}
		return cdcStartTs;
	}

	private void setDefaultRowSizeMap() {
		for (String tableName : dataProcessorContext.getTapTableMap().keySet()) {
			if (null == snapshotRowSizeMap) {
				snapshotRowSizeMap = new HashMap<>();
			}
			snapshotRowSizeMap.putIfAbsent(tableName, 0L);
		}
	}

	@Override
	public void doClose() throws Exception {
		try {
			CommonUtils.handleAnyError(() -> {
				if (null != shareCdcReader) {
					shareCdcReader.close();
				}
			}, err -> obsLogger.warn(String.format("Close share cdc log reader failed: %s", err.getMessage())));
		} finally {
			super.doClose();
		}
	}
}
