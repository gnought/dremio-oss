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
package com.dremio.exec.planner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.util.JsonStringArrayList;
import org.apache.arrow.vector.util.Text;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.dremio.PlanTestBase;
import com.dremio.common.exceptions.UserRemoteException;
import com.dremio.exec.fn.interp.TestConstantFolding;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

public class TestDirectoryExplorerUDFs extends PlanTestBase {

  private static class ConstantFoldingTestConfig {
    String funcName;
    String expectedFolderName;
    public ConstantFoldingTestConfig(String funcName, String expectedFolderName) {
      this.funcName = funcName;
      this.expectedFolderName = expectedFolderName;
    }
  }

  private static List<ConstantFoldingTestConfig> tests;
  private String path;

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @BeforeClass
  public static void init() {
    // Need the suffixes to make the names unique in the directory.
    // The capitalized name is on the opposite function (imaxdir and mindir)
    // because they are looking on opposite ends of the list.
    //
    // BIGFILE_2 with the capital letter at the start of the name comes
    // first in the case-sensitive ordering.
    // SMALLFILE_2 comes last in a case-insensitive ordering because it has
    // a suffix not found on smallfile.
    tests = ImmutableList.<ConstantFoldingTestConfig>builder()
        .add(new ConstantFoldingTestConfig("MAXDIR", "smallfile"))
        .add(new ConstantFoldingTestConfig("IMAXDIR", "SMALLFILE_2"))
        .add(new ConstantFoldingTestConfig("MINDIR", "BIGFILE_2"))
        .add(new ConstantFoldingTestConfig("IMINDIR", "bigfile"))
        .build();
  }

  @Before
  public void setup() throws Exception {
    new TestConstantFolding.SmallFileCreator(folder).createFiles(1, 1000);
    path = folder.getRoot().toPath().toString();
  }


  @Ignore
  @Test
  public void testConstExprFolding_maxDir0() throws Exception {

    test("use dfs_root");

    List<String> allFiles = ImmutableList.<String>builder()
        .add("smallfile")
        .add("SMALLFILE_2")
        .add("bigfile")
        .add("BIGFILE_2")
        .build();

    String query = "select * from dfs_root.\"" + path + "/*/*.csv\" where dir0 = %s('dfs','" + path + "')";
    for (ConstantFoldingTestConfig config : tests) {
      // make all of the other folders unexpected patterns, except for the one expected in this case
      List<String> excludedPatterns = Lists.newArrayList();
      excludedPatterns.addAll(allFiles);
      excludedPatterns.remove(config.expectedFolderName);
      // The list is easier to construct programmatically, but the API below takes an array to make it easier
      // to write a list as a literal array in a typical test definition
      String[] excludedArray = new String[excludedPatterns.size()];

      testPlanMatchingPatterns(
          String.format(query, config.funcName),
          new String[] {config.expectedFolderName},
          excludedPatterns.toArray(excludedArray));
    }

    JsonStringArrayList<Text> list = new JsonStringArrayList<>();

    list.add(new Text("1"));
    list.add(new Text("2"));
    list.add(new Text("3"));

    testBuilder()
        .sqlQuery(String.format(query, tests.get(0).funcName))
        .unOrdered()
        .baselineColumns("columns", "dir0")
        .baselineValues(list, tests.get(0).expectedFolderName)
        .go();
  }

  @Ignore
  @Test
  public void testIncorrectFunctionPlacement() throws Exception {

    Map<String, String> configMap = ImmutableMap.<String, String>builder()
        .put("select %s('dfs_root','" + path + "') from dfs.\"" + path + "/*/*.csv\"", "Select List")
        .put("select dir0 from dfs.\"" + path + "/*/*.csv\" order by %s('dfs_root','" + path + "')", "Order By")
        .put("select max(dir0) from dfs.\"" + path + "/*/*.csv\" group by %s('dfs_root','" + path + "')", "Group By")
        .put("select concat(concat(%s('dfs_root','" + path + "'),'someName'),'someName') from dfs.\"" + path + "/*/*.csv\"", "Select List")
        .put("select dir0 from dfs.\"" + path + "/*/*.csv\" order by concat(%s('dfs_root','" + path + "'),'someName')", "Order By")
        .put("select max(dir0) from dfs.\"" + path + "/*/*.csv\" group by concat(%s('dfs_root','" + path + "'),'someName')", "Group By")
        .build();

    for (Map.Entry<String, String> configEntry : configMap.entrySet()) {
      for (ConstantFoldingTestConfig functionConfig : tests) {
        assertThatThrownBy(() -> test(String.format(configEntry.getKey(), functionConfig.funcName)))
          .isInstanceOf(UserRemoteException.class)
          .hasMessageContaining("Directory explorers [MAXDIR, IMAXDIR, MINDIR, IMINDIR] functions are not supported in %s", configEntry.getValue());
      }
    }
  }

  @Ignore
  @Test
  public void testConstantFoldingOff() throws Exception {
    try {
      test("set \"planner.enable_constant_folding\" = false;");
      String query = "select * from dfs.\"" + path + "/*/*.csv\" where dir0 = %s('dfs_root','" + path + "')";
      for (ConstantFoldingTestConfig config : tests) {
        assertThatThrownBy(() -> test(String.format(query, config.funcName)))
          .isInstanceOf(UserRemoteException.class)
          .hasMessageContaining("Directory explorers [MAXDIR, IMAXDIR, MINDIR, IMINDIR] functions can not be used " +
              "when planner.enable_constant_folding option is set to false");
      }
    } finally {
      test("set \"planner.enable_constant_folding\" = true;");
    }
  }
}
