// Copyright 2014 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.planner;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.AnalyticWindow;
import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.ExprSubstitutionMap;
import com.cloudera.impala.analysis.TupleDescriptor;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.thrift.TAnalyticNode;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TPlanNodeType;
import com.cloudera.impala.thrift.TQueryOptions;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Computation of analytic exprs.
 */
public class AnalyticEvalNode extends PlanNode {
  private final static Logger LOG = LoggerFactory.getLogger(AnalyticEvalNode.class);

  private List<Expr> analyticFnCalls_;
  // Partitioning exprs from the AnalyticInfo
  private final List<Expr> partitionExprs_;
  // TODO: Remove when the BE uses partitionByLessThan rather than the exprs
  private List<Expr> substitutedPartitionExprs_;
  private List<Expr> orderingExprs_;
  private final AnalyticWindow analyticWindow_;
  private final TupleDescriptor intermediateTupleDesc_;
  private final TupleDescriptor outputTupleDesc_;
  private final ExprSubstitutionMap analyticSmap_;

  // predicates constructed from partitionExprs_/orderingExprs_ to
  // compare input to buffered tuples
  private final Expr partitionByLessThan_;
  private final Expr orderByLessThan_;
  private final TupleDescriptor bufferedTupleDesc_;

  public AnalyticEvalNode(
      PlanNodeId id, PlanNode input, List<Expr> analyticFnCalls,
      List<Expr> partitionExprs, List<Expr> orderingExprs,
      AnalyticWindow analyticWindow, TupleDescriptor intermediateTupleDesc,
      TupleDescriptor outputTupleDesc,
      ExprSubstitutionMap analyticSmap,
      Expr partitionByLessThan, Expr orderByLessThan,
      TupleDescriptor bufferedTupleDesc) {
    super(id, input.getTupleIds(), "ANALYTIC");
    Preconditions.checkState(!tupleIds_.contains(outputTupleDesc.getId()));
    // we're materializing the input row augmented with the analytic output tuple
    tupleIds_.add(outputTupleDesc.getId());
    analyticFnCalls_ = analyticFnCalls;
    partitionExprs_ = partitionExprs;
    orderingExprs_ = orderingExprs;
    analyticWindow_ = analyticWindow;
    intermediateTupleDesc_ = intermediateTupleDesc;
    outputTupleDesc_ = outputTupleDesc;
    analyticSmap_ = analyticSmap;
    partitionByLessThan_ = partitionByLessThan;
    orderByLessThan_ = orderByLessThan;
    bufferedTupleDesc_ = bufferedTupleDesc;
    children_.add(input);
    nullableTupleIds_.addAll(input.getNullableTupleIds());
  }

  @Override
  public boolean isBlockingNode() { return true; }
  public List<Expr> getPartitionExprs() { return partitionExprs_; }
  public List<Expr> getOrderingExprs() { return orderingExprs_; }

  @Override
  public void init(Analyzer analyzer) throws InternalException {
    // TODO: deal with conjuncts
    computeMemLayout(analyzer);

    // do this at the end so it can take all conjuncts into account
    computeStats(analyzer);

    // we add the analyticInfo's smap to the combined smap of our child
    baseTblSmap_ = analyticSmap_;
    createDefaultSmap(analyzer);

    LOG.trace("desctbl: " + analyzer.getDescTbl().debugString());

    // point fn calls, partition and ordering exprs at our input
    ExprSubstitutionMap childSmap = getCombinedChildSmap();
    analyticFnCalls_ = Expr.substituteList(analyticFnCalls_, childSmap, analyzer);
    substitutedPartitionExprs_ = Expr.substituteList(partitionExprs_, childSmap,
        analyzer);
    orderingExprs_ = Expr.substituteList(orderingExprs_, childSmap, analyzer);
    LOG.trace("evalnode: " + debugString());
  }

  @Override
  protected void computeStats(Analyzer analyzer) {
    super.computeStats(analyzer);
    cardinality_ = getChild(0).cardinality_;
  }

  @Override
  protected String debugString() {
    return Objects.toStringHelper(this)
        .add("analyticFnCalls", Expr.debugString(analyticFnCalls_))
        .add("partitionExprs", Expr.debugString(partitionExprs_))
        .add("subtitutedPartitionExprs", Expr.debugString(substitutedPartitionExprs_))
        .add("orderingExprs", Expr.debugString(orderingExprs_))
        .add("window", analyticWindow_)
        .add("intermediateTid", intermediateTupleDesc_.getId())
        .add("outputTid", outputTupleDesc_.getId())
        .add("partitionByLt",
            partitionByLessThan_ != null ? partitionByLessThan_.debugString() : "null")
        .add("orderByLt",
            orderByLessThan_ != null ? orderByLessThan_.debugString() : "null")
        .addValue(super.debugString())
        .toString();
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    msg.node_type = TPlanNodeType.ANALYTIC_EVAL_NODE;
    msg.analytic_node = new TAnalyticNode();
    msg.analytic_node.setIntermediate_tuple_id(intermediateTupleDesc_.getId().asInt());
    msg.analytic_node.setOutput_tuple_id(outputTupleDesc_.getId().asInt());
    msg.analytic_node.setPartition_exprs(Expr.treesToThrift(substitutedPartitionExprs_));
    msg.analytic_node.setOrder_by_exprs(Expr.treesToThrift(orderingExprs_));
    msg.analytic_node.setAnalytic_functions(Expr.treesToThrift(analyticFnCalls_));
    if (analyticWindow_ == null) {
      if (!orderingExprs_.isEmpty()) {
        msg.analytic_node.setWindow(AnalyticWindow.DEFAULT_WINDOW.toThrift());
      }
    } else {
      msg.analytic_node.setWindow(analyticWindow_.toThrift());
    }
    if (partitionByLessThan_ != null) {
      msg.analytic_node.setPartition_by_lt(partitionByLessThan_.treeToThrift());
    }
    if (orderByLessThan_ != null) {
      msg.analytic_node.setOrder_by_lt(orderByLessThan_.treeToThrift());
    }
    if (bufferedTupleDesc_ != null) {
      msg.analytic_node.setBuffered_tuple_id(bufferedTupleDesc_.getId().asInt());
    }
  }

  @Override
  protected String getNodeExplainString(String prefix, String detailPrefix,
      TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    output.append(String.format("%s%s", prefix, getDisplayLabel()));
    output.append("\n");
    if (detailLevel.ordinal() >= TExplainLevel.STANDARD.ordinal()) {
      output.append(detailPrefix + "functions: ");
      List<String> strings = Lists.newArrayList();
      for (Expr fnCall: analyticFnCalls_) {
        strings.add(fnCall.toSql());
      }
      output.append(Joiner.on(", ").join(strings));
      output.append("\n");

      if (!partitionExprs_.isEmpty()) {
        output.append(detailPrefix + "partition by: ");
        strings.clear();
        for (Expr partitionExpr: partitionExprs_) {
          strings.add(partitionExpr.toSql());
        }
        output.append(Joiner.on(", ").join(strings));
        output.append("\n");
      }

      if (!orderingExprs_.isEmpty()) {
        output.append(detailPrefix + "order by: ");
        strings.clear();
        for (Expr orderingExpr: orderingExprs_) {
          strings.add(orderingExpr.toSql());
        }
        output.append(Joiner.on(", ").join(strings));
        output.append("\n");
      }

      if (analyticWindow_ != null) {
        output.append(detailPrefix + "window: ");
        output.append(analyticWindow_.toSql());
        output.append("\n");
      }
    }
    return output.toString();
  }

  @Override
  public void computeCosts(TQueryOptions queryOptions) {
    Preconditions.checkNotNull(fragment_,
        "PlanNode must be placed into a fragment before calling this method.");
    // TODO: come up with estimate based on window
    perHostMemCost_ = 0;
  }
}