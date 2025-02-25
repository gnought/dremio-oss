/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dremio.exec.physical.impl.join;

import org.junit.Test;

import com.dremio.PlanTestBase;
import com.dremio.common.util.TestTools;
import com.dremio.exec.planner.physical.PlannerSettings;

public class TestNestedLoopJoin extends PlanTestBase {

  private static String nlpattern = "NestedLoopJoin";
  private static final String WORKING_PATH = TestTools.getWorkingPath();
  private static final String TEST_RES_PATH = WORKING_PATH + "/src/test/resources";

  private static final String NLJ = "alter session set \"planner.enable_hashjoin\" = false; " +
      "alter session set \"planner.enable_mergejoin\" = false; ";
  private static final String SINGLE_NLJ = "alter session set \"planner.disable_exchanges\" = true; " + NLJ;
  private static final String DISABLE_HJ = "alter session set \"planner.enable_hashjoin\" = false";
  private static final String ENABLE_HJ = "alter session set \"planner.enable_hashjoin\" = true";
  private static final String DISABLE_MJ = "alter session set \"planner.enable_mergejoin\" = false";
  private static final String ENABLE_MJ = "alter session set \"planner.enable_mergejoin\" = true";

  // Test queries used by planning and execution tests
  private static final String testNlJoinExists_1 = "select r_regionkey from cp.\"tpch/region.parquet\" "
      + " where exists (select n_regionkey from cp.\"tpch/nation.parquet\" "
      + " where n_nationkey < 10)";

  private static final String testNlJoinNotIn_1 = "select r_regionkey from cp.\"tpch/region.parquet\" "
      + " where r_regionkey not in (select n_regionkey from cp.\"tpch/nation.parquet\" "
      + "                            where n_nationkey < 4)";

  // not-in subquery produces empty set
  private static final String testNlJoinNotIn_2 = "select r_regionkey from cp.\"tpch/region.parquet\" "
      + " where r_regionkey not in (select n_regionkey from cp.\"tpch/nation.parquet\" "
      + "                            where 1=0)";

  private static final String testNlJoinInequality_1 = "select r_regionkey from cp.\"tpch/region.parquet\" "
      + " where r_regionkey > (select min(n_regionkey) from cp.\"tpch/nation.parquet\" "
      + "                        where n_nationkey < 4)";

  private static final String testNlJoinInequality_2 = "select r.r_regionkey, n.n_nationkey from cp.\"tpch/nation.parquet\" n "
      + " inner join cp.\"tpch/region.parquet\" r on n.n_regionkey < r.r_regionkey where n.n_nationkey < 3";

  private static final String testNlJoinInequality_3 = "select r_regionkey from cp.\"tpch/region.parquet\" "
      + " where r_regionkey > (select min(n_regionkey) * 2 from cp.\"tpch/nation.parquet\" )";

  private static final String testNlJoinProjectedFields_null = "select *, ROW_NUMBER() over() from cp.\"tpch/customer.parquet\" "
    + " right join cp.\"tpch/nation.parquet\" on n_nationkey >= c_nationkey";

  @Test
  public void testNlJoinProjectedFields_null() throws Exception {
    testPlanMatchingPatterns(testNlJoinProjectedFields_null, new String[]{nlpattern});
  }

  @Test
  public void testNlJoinProjectedFields_empty() throws Exception {
    String query = "select count(*) from "
      + "cp.\"tpch/supplier.parquet\" inner join "
      + "(select * from cp.\"tpch/nation.parquet\" inner join cp.\"tpch/region.parquet\" on r_regionkey = n_regionkey) "
      + "on true";
    testPlanMatchingPatterns(query, new String[]{nlpattern});
  }

  @Test
  public void testNlJoinWithLeftOuterJoin() throws Exception {
    try (AutoCloseable ignored = withOption(PlannerSettings.EXTRA_CONDITIONS_HASHJOIN, false)) {
      String query = "SELECT * FROM cp.\"tpch/orders.parquet\" o \n" +
        "LEFT OUTER JOIN cp.\"tpch/lineitem.parquet\" l \n" +
        "ON o.o_orderkey = l.l_orderkey\n" +
        "AND o.o_custkey = 63190\n" +
        "AND o.o_totalprice / l.l_quantity > 100.0";
      testPlanMatchingPatterns(query, new String[]{nlpattern});
    }
  }

  @Test
  public void testNlJoinExists_1_planning() throws Exception {
    testPlanMatchingPatterns(testNlJoinExists_1, new String[]{nlpattern});
  }

  @Test
  public void testNlJoinNotIn_1_planning() throws Exception {
    testPlanMatchingPatterns(testNlJoinNotIn_1, new String[]{nlpattern});
  }

  @Test
  public void testNlJoinInequality_1() throws Exception {
    testPlanMatchingPatterns(testNlJoinInequality_1, new String[]{nlpattern});
  }

  @Test
  public void testNlJoinInequality_2() throws Exception {
    testPlanMatchingPatterns(testNlJoinInequality_2, new String[]{nlpattern});
  }

  @Test
  public void testNlJoinInequality_3() throws Exception {
    testPlanMatchingPatterns(testNlJoinInequality_3, new String[]{nlpattern});
  }

  @Test
  public void testNlJoinAggrs_1_planning() throws Exception {
    String query = "select total1, total2 from "
       + "(select sum(l_quantity) as total1 from cp.\"tpch/lineitem.parquet\" where l_suppkey between 100 and 200), "
       + "(select sum(l_quantity) as total2 from cp.\"tpch/lineitem.parquet\" where l_suppkey between 200 and 300)  ";
    testPlanMatchingPatterns(query, new String[]{nlpattern});
  }

  @Test // equality join and scalar right input, hj and mj disabled
  public void testNlJoinEqualityScalar_1_planning() throws Exception {
    String query = "select r_regionkey from cp.\"tpch/region.parquet\" "
        + " where r_regionkey = (select min(n_regionkey) from cp.\"tpch/nation.parquet\" "
        + "                        where n_nationkey < 10)";
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    testPlanMatchingPatterns(query, new String[]{nlpattern});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
  }

  @Test // equality join and scalar right input, hj and mj disabled, enforce exchanges
  public void testNlJoinEqualityScalar_2_planning() throws Exception {
    String query = "select r_regionkey from cp.\"tpch/region.parquet\" "
        + " where r_regionkey = (select min(n_regionkey) from cp.\"tpch/nation.parquet\" "
        + "                        where n_nationkey < 10)";
    test("alter session set \"planner.slice_target\" = 1");
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    testPlanMatchingPatterns(query, new String[]{nlpattern, "BroadcastExchange"});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
    test("alter session set \"planner.slice_target\" = 100000");
  }

  @Test // equality join and non-scalar right input, hj and mj disabled
  public void testNlJoinEqualityNonScalar_1_planning() throws Exception {
    String query = "select r.r_regionkey from cp.\"tpch/region.parquet\" r inner join cp.\"tpch/nation.parquet\" n"
        + " on r.r_regionkey = n.n_regionkey where n.n_nationkey < 10";
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    testPlanMatchingPatterns(query, new String[]{nlpattern});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
  }

  @Test // equality join and non-scalar right input, hj and mj disabled, enforce exchanges
  public void testNlJoinEqualityNonScalar_2_planning() throws Exception {
    String query = String.format("select n.n_nationkey from cp.\"tpch/nation.parquet\" n, "
        + " dfs_root.\"%s/multilevel/parquet\" o "
        + " where n.n_regionkey = o.o_orderkey and o.o_custkey < 5", TEST_RES_PATH);
    test("alter session set \"planner.slice_target\" = 1");
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    testPlanMatchingPatterns(query, new String[]{nlpattern, "BroadcastExchange"});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
    test("alter session set \"planner.slice_target\" = 100000");
  }

  // EXECUTION TESTS

  @Test
  public void testNlJoinExists_1_exec() throws Exception {
    testBuilder()
        .sqlQuery(testNlJoinExists_1)
        .unOrdered()
        .baselineColumns("r_regionkey")
        .baselineValues(0)
        .baselineValues(1)
        .baselineValues(2)
        .baselineValues(3)
        .baselineValues(4)
        .go();
  }

  @Test
  public void testNlJoinNotIn_1_exec() throws Exception {
    testBuilder()
        .sqlQuery(testNlJoinNotIn_1)
        .unOrdered()
        .baselineColumns("r_regionkey")
        .baselineValues(2)
        .baselineValues(3)
        .baselineValues(4)
        .go();
  }

  @Test
  public void testNlJoinNotIn_2_exec() throws Exception {
    testBuilder()
        .sqlQuery(testNlJoinNotIn_2)
        .unOrdered()
        .baselineColumns("r_regionkey")
        .baselineValues(0)
        .baselineValues(1)
        .baselineValues(2)
        .baselineValues(3)
        .baselineValues(4)
        .go();
  }

  @Test
  public void testNLJWithEmptyBatch() throws Exception {
    Long result = 0L;

    test(DISABLE_HJ);
    test(DISABLE_MJ);

    // We have a false filter causing empty left batch
    String query = "select count(*) col from (select a.last_name " +
      "from cp.\"employee.json\" a " +
      "where exists (select n_name from cp.\"tpch/nation.parquet\" b) AND 1 = 0)";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(result)
        .go();

    // Below tests use NLJ in a general case (non-scalar subqueries, followed by filter) with empty batches
    query = "select count(*) col from " +
        "(select t1.department_id " +
        "from cp.\"employee.json\" t1 inner join cp.\"department.json\" t2 " +
        "on t1.department_id = t2.department_id where t1.department_id = -1)";

    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(result)
        .go();

    query = "select count(*) col from " +
        "(select t1.department_id " +
        "from cp.\"employee.json\" t1 inner join cp.\"department.json\" t2 " +
        "on t1.department_id = t2.department_id where t2.department_id = -1)";


    testBuilder()
        .sqlQuery(query)
        .unOrdered()
        .baselineColumns("col")
        .baselineValues(result)
        .go();

    test(ENABLE_HJ);
    test(ENABLE_MJ);
  }

  @Test
  public void testDx35886() throws Exception {
    test("SELECT * FROM cp.tpch_json.\"orders.json\" o \n" +
      "left outer join cp.tpch_json.\"lineitem.json\" l \n" +
      "on o.o_orderkey = l.l_orderkey\n" +
      "AND o.o_orderstatus = l.l_linestatus\n" +
      "AND o.o_custkey > 63190\n" +
      "AND o.o_totalprice / l.l_quantity > 100.0");
  }
}
