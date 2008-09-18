/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.parse;
/*
 * PartitionPruner.java
 *
 * Created on April 9, 2008, 3:48 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

import java.util.*;
import org.antlr.runtime.tree.*;

import org.apache.hadoop.hive.ql.exec.ExprNodeEvaluator;
import org.apache.hadoop.hive.ql.exec.ExprNodeEvaluatorFactory;
import org.apache.hadoop.hive.ql.exec.HiveObject;
import org.apache.hadoop.hive.ql.exec.LabeledCompositeHiveObject;
import org.apache.hadoop.hive.ql.exec.PrimitiveHiveObject;
import org.apache.hadoop.hive.ql.metadata.*;
import org.apache.hadoop.hive.ql.plan.exprNodeColumnDesc;
import org.apache.hadoop.hive.ql.plan.exprNodeConstantDesc;
import org.apache.hadoop.hive.ql.plan.exprNodeDesc;
import org.apache.hadoop.hive.ql.plan.exprNodeFieldDesc;
import org.apache.hadoop.hive.ql.plan.exprNodeFuncDesc;
import org.apache.hadoop.hive.ql.plan.exprNodeIndexDesc;
import org.apache.hadoop.hive.ql.plan.exprNodeNullDesc;
import org.apache.hadoop.hive.ql.udf.UDFOPAnd;
import org.apache.hadoop.hive.ql.udf.UDFOPNot;
import org.apache.hadoop.hive.ql.udf.UDFOPOr;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class PartitionPruner {
    
  // The log
  @SuppressWarnings("nls")
  private static final Log LOG = LogFactory.getLog("hive.ql.parse.PartitionPruner");
 
  private String tableAlias;

  private QBMetaData metaData;
  
  private Table tab;

  private exprNodeDesc prunerExpr;

  /** Creates a new instance of PartitionPruner */
  public PartitionPruner(String tableAlias, QBMetaData metaData) {
    this.tableAlias = tableAlias;
    this.metaData = metaData;
    this.tab = metaData.getTableForAlias(tableAlias);
    this.prunerExpr = new exprNodeConstantDesc(Boolean.TRUE);
  }

  /**
   * We use exprNodeConstantDesc(class,null) to represent unknown values.
   * Except UDFOPAnd, UDFOPOr, and UDFOPNot, all UDFs are assumed to return unknown values 
   * if any of the arguments are unknown.  
   *  
   * @param expr
   * @return The expression desc, will NEVER be null.
   * @throws SemanticException
   */
  @SuppressWarnings("nls")
  private exprNodeDesc genExprNodeDesc(CommonTree expr)
  throws SemanticException {
    //  We recursively create the exprNodeDesc.  Base cases:  when we encounter 
    //  a column ref, we convert that into an exprNodeColumnDesc;  when we encounter 
    //  a constant, we convert that into an exprNodeConstantDesc.  For others we just 
    //  build the exprNodeFuncDesc with recursively built children.

    exprNodeDesc desc = null;

    //  Is this a simple expr node (not a TOK_COLREF or a TOK_FUNCTION or an operator)?
    desc = SemanticAnalyzer.genSimpleExprNodeDesc(expr);
    if (desc != null) {
      return desc;
    }

    int tokType = expr.getType();
    switch (tokType) {
      case HiveParser.TOK_COLREF: {

        assert(expr.getChildCount() == 2);
        String tabAlias = SemanticAnalyzer.getTableName(expr);
        String colName = SemanticAnalyzer.getSerDeFieldExpression(expr);
        if (tabAlias == null || colName == null) {
          throw new SemanticException(ErrorMsg.INVALID_XPATH.getMsg(expr));
        }
        // Set value to null if it's not partition column
        if (tabAlias.equals(tableAlias) && tab.isPartitionKey(colName)) {
          desc = new exprNodeColumnDesc(String.class, colName); 
        } else {
          // might be a column from another table
          TypeInfo typeInfo = new TypeInfo(this.metaData.getTableForAlias(tabAlias).getSerDe(), null);
          desc = new exprNodeConstantDesc(typeInfo.getFieldType(colName), null);
        }
        break;
      }

      default: {
        boolean isFunction = (expr.getType() == HiveParser.TOK_FUNCTION);
        
        // Create all children
        int childrenBegin = (isFunction ? 1 : 0);
        ArrayList<exprNodeDesc> children = new ArrayList<exprNodeDesc>(expr.getChildCount() - childrenBegin);
        for (int ci=childrenBegin; ci<expr.getChildCount(); ci++) {
          children.add(genExprNodeDesc((CommonTree)expr.getChild(ci)));
        }

        // Create function desc
        desc = SemanticAnalyzer.getXpathOrFuncExprNodeDesc(expr, isFunction, children);
        
        if (desc instanceof exprNodeFuncDesc && (
            ((exprNodeFuncDesc)desc).getUDFMethod().getDeclaringClass().equals(UDFOPAnd.class) 
            || ((exprNodeFuncDesc)desc).getUDFMethod().getDeclaringClass().equals(UDFOPOr.class)
            || ((exprNodeFuncDesc)desc).getUDFMethod().getDeclaringClass().equals(UDFOPNot.class))) {
          // do nothing because "And" and "Or" and "Not" supports null value evaluation
          // NOTE: In the future all UDFs that treats null value as UNKNOWN (both in parameters and return 
          // values) should derive from a common base class UDFNullAsUnknown, so instead of listing the classes
          // here we would test whether a class is derived from that base class. 
        } else {
          // If any child is null, set this node to null
          if (mightBeUnknown(desc)) {
            LOG.trace("Pruner function might be unknown: " + expr.toStringTree());
            desc = new exprNodeConstantDesc(desc.getTypeInfo(), null);
          }
        }      
        break;
      }
    }
    return desc;
  }  
  
  public static boolean mightBeUnknown(exprNodeDesc desc) {
    if (desc instanceof exprNodeConstantDesc) {
      exprNodeConstantDesc d = (exprNodeConstantDesc)desc;
      return d.getValue() == null;
    } else if (desc instanceof exprNodeNullDesc) {
      return false;
    } else if (desc instanceof exprNodeIndexDesc) {
      exprNodeIndexDesc d = (exprNodeIndexDesc)desc;
      return mightBeUnknown(d.getDesc()) || mightBeUnknown(d.getIndex());
    } else if (desc instanceof exprNodeFieldDesc) {
      exprNodeFieldDesc d = (exprNodeFieldDesc)desc;
      return mightBeUnknown(d.getDesc());
    } else if (desc instanceof exprNodeFuncDesc) {
      exprNodeFuncDesc d = (exprNodeFuncDesc)desc;
      for(int i=0; i<d.getChildren().size(); i++) {
        if (mightBeUnknown(d.getChildren().get(i))) {
          return true;
        }
      }
      return false;
    } else if (desc instanceof exprNodeColumnDesc) {
      return false;
    }
    return false;
  }
  
  /** Add an expression */
  @SuppressWarnings("nls")
  public void addExpression(CommonTree expr) throws SemanticException {
    LOG.trace("adding pruning Tree = " + expr.toStringTree());
    exprNodeDesc desc = genExprNodeDesc(expr);
    // Ignore null constant expressions
    if (!(desc instanceof exprNodeConstantDesc) || ((exprNodeConstantDesc)desc).getValue() != null ) {
      LOG.trace("adding pruning expr = " + desc);
      this.prunerExpr = SemanticAnalyzer.getFuncExprNodeDesc("AND", this.prunerExpr, desc);
    }
  }
  
  /** From the table metadata prune the partitions to return the partitions **/
  @SuppressWarnings("nls")
  public Set<Partition> prune() throws HiveException {
    LOG.trace("Started pruning partiton");
    LOG.trace("tabname = " + this.tab.getName());
    LOG.trace("prune Expression = " + this.prunerExpr);

    HashSet<Partition> ret_parts = new HashSet<Partition>();
    try {
      ExprNodeEvaluator evaluator = ExprNodeEvaluatorFactory.get(this.prunerExpr);
      for(Partition part: Hive.get().getPartitions(this.tab)) {
        // Set all the variables here
        LinkedHashMap<String, String> partSpec = part.getSpec();

        // Create the row object
        String[] partNames = new String[partSpec.size()];
        int i=0;
        for(String name: partSpec.keySet()) {
          partNames[i++] = name;
        }
        LabeledCompositeHiveObject hiveObject;
        hiveObject = new LabeledCompositeHiveObject(partNames);
        for(String s: partNames) {
          hiveObject.addHiveObject(new PrimitiveHiveObject(partSpec.get(s)));
        }
        
        // evaluate the expression tree
        HiveObject r = evaluator.evaluate(hiveObject);
        LOG.trace("prune result for partition " + partSpec + ": " + r.getJavaObject());
        if (!Boolean.FALSE.equals(r.getJavaObject())) {
          LOG.debug("retained partition: " + partSpec);
          ret_parts.add(part);
        } else {
          LOG.trace("pruned partition: " + partSpec);
        }
      }
    }
    catch (Exception e) {
      throw new HiveException(e);
    }

    // Now return the set of partitions
    return ret_parts;
  }

  public Table getTable() {
    return this.tab;
  }
}
