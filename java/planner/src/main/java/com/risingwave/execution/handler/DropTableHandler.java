package com.risingwave.execution.handler;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.risingwave.catalog.TableCatalog;
import com.risingwave.common.exception.PgErrorCode;
import com.risingwave.common.exception.PgException;
import com.risingwave.execution.context.ExecutionContext;
import com.risingwave.execution.result.DdlResult;
import com.risingwave.pgwire.database.PgResult;
import com.risingwave.pgwire.msg.StatementType;
import com.risingwave.proto.common.Status;
import com.risingwave.proto.computenode.CreateTaskRequest;
import com.risingwave.proto.computenode.CreateTaskResponse;
import com.risingwave.proto.computenode.GetDataRequest;
import com.risingwave.proto.plan.DropSourceNode;
import com.risingwave.proto.plan.DropTableNode;
import com.risingwave.proto.plan.ExchangeInfo;
import com.risingwave.proto.plan.PlanFragment;
import com.risingwave.proto.plan.PlanNode;
import com.risingwave.proto.plan.TaskSinkId;
import com.risingwave.rpc.ComputeClient;
import com.risingwave.rpc.ComputeClientManager;
import com.risingwave.rpc.Messages;
import com.risingwave.scheduler.streaming.RemoteStreamManager;
import com.risingwave.scheduler.streaming.StreamManager;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.ddl.SqlDropTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DropTableHandler is the handler of both DropTable and DropSource. It determines the type by
 * `isSource`.
 */
@HandlerSignature(sqlKinds = {SqlKind.DROP_TABLE})
public class DropTableHandler implements SqlHandler {
  private static final Logger log = LoggerFactory.getLogger(DropMaterializedViewHandler.class);

  @Override
  public PgResult handle(SqlNode ast, ExecutionContext context) {
    var planFragments = execute(ast, context);
    ComputeClientManager clientManager = context.getComputeClientManager();

    for (var planFragment : planFragments) {
      for (var node : context.getWorkerNodeManager().allNodes()) {
        ComputeClient client = clientManager.getOrCreate(node);
        CreateTaskRequest createTaskRequest = Messages.buildCreateTaskRequest(planFragment);
        CreateTaskResponse createTaskResponse = client.createTask(createTaskRequest);
        if (createTaskResponse.getStatus().getCode() != Status.Code.OK) {
          throw new PgException(PgErrorCode.INTERNAL_ERROR, "Create Task failed");
        }
        TaskSinkId taskSinkId = Messages.buildTaskSinkId(createTaskRequest.getTaskId());
        client.getData(GetDataRequest.newBuilder().setSinkId(taskSinkId).build());
      }
    }

    return new DdlResult(StatementType.DROP_TABLE, 0);
  }

  private static PlanFragment serialize(TableCatalog table) {
    TableCatalog.TableId tableId = table.getId();
    ExchangeInfo exchangeInfo =
        ExchangeInfo.newBuilder().setMode(ExchangeInfo.DistributionMode.SINGLE).build();

    PlanNode rootNode;
    if (table.isSource()) {
      DropSourceNode dropSourceNode =
          DropSourceNode.newBuilder().setTableRefId(Messages.getTableRefId(tableId)).build();
      rootNode = PlanNode.newBuilder().setDropSource(dropSourceNode).build();
    } else {
      DropTableNode dropTableNode =
          DropTableNode.newBuilder().setTableRefId(Messages.getTableRefId(tableId)).build();
      rootNode = PlanNode.newBuilder().setDropTable(dropTableNode).build();
    }

    return PlanFragment.newBuilder().setRoot(rootNode).setExchangeInfo(exchangeInfo).build();
  }

  protected ImmutableList<TableCatalog> resolveTableToDrop(SqlNode ast, ExecutionContext context) {
    var sql = (SqlDropTable) ast;
    var name = sql.name.getSimple();
    var tableName = TableCatalog.TableName.of(context.getDatabase(), context.getSchema(), name);
    var table = context.getCatalogService().getTable(tableName);

    var builder = ImmutableList.<TableCatalog>builder();

    if (table == null) {
      if (!sql.ifExists) {
        throw new PgException(PgErrorCode.UNDEFINED_TABLE, "Table does not exist");
      }
    } else {
      builder.add(table);

      if (table.isAssociatedMaterializedView()) {
        var sourceName = TableCatalog.getTableSourceName(name);
        var sourceTableName =
            TableCatalog.TableName.of(context.getDatabase(), context.getSchema(), sourceName);
        var sourceTable = context.getCatalogService().getTable(sourceTableName);
        checkNotNull(sourceTable, "source table for associated materialized view must exist");
        builder.add(sourceTable);
      }
    }

    return builder.build();
  }

  @VisibleForTesting
  protected ImmutableList<PlanFragment> execute(SqlNode ast, ExecutionContext context) {
    var tables = resolveTableToDrop(ast, context);
    var builder = ImmutableList.<PlanFragment>builder();
    for (var table : tables) {
      var name = table.getEntityName();
      context.getCatalogService().dropTable(name);
      log.debug("drop table:\n" + name);
      if (table.isAssociatedMaterializedView()) {
        log.debug("drop associated materialized view:\n" + name);
        StreamManager streamManager = context.getStreamManager();
        if (streamManager instanceof RemoteStreamManager) {
          RemoteStreamManager remoteStreamManager = (RemoteStreamManager) streamManager;
          remoteStreamManager.dropMaterializedView(Messages.getTableRefId(table.getId()));
        } else {
          throw new PgException(
              PgErrorCode.INTERNAL_ERROR, "Not available in local stream manager");
        }
      } else {
        var planFragment = serialize(table);
        builder.add(planFragment);
      }
    }
    return builder.build();
  }
}
