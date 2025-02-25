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
package com.dremio.exec.catalog.dataplane;

import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.DATAPLANE_PLUGIN_NAME;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.DEFAULT_BRANCH_NAME;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.DEFAULT_COUNT_COLUMN;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.createBranchAtBranchQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.createEmptyTableQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.createEmptyTableQueryWithAt;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.createFolderAtQueryWithIfNotExists;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.createTableAsQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.createTagQueryWithFrom;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.dropBranchForceQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.dropTableQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.dropTableQueryWithAt;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.dropTagQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.generateUniqueBranchName;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.generateUniqueFolderName;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.generateUniqueTableName;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.generateUniqueTagName;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.insertSelectQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.insertTableAtQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.insertTableAtQueryWithRef;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.insertTableAtQueryWithSelect;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.insertTableQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.joinedTableKey;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.mergeBranchQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.selectCountQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.tablePathWithFolders;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.useBranchQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.useCommitQuery;
import static com.dremio.exec.catalog.dataplane.DataplaneTestDefines.useTagQuery;
import static com.dremio.exec.catalog.dataplane.TestDataplaneAssertions.assertIcebergFilesExistAtSubPath;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.catalog.model.dataset.TableVersionType;


public class ITDataplanePluginInsert extends ITDataplanePluginTestSetup {

  @Test
  public void insertIntoEmpty() throws Exception {
    // Arrange
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(tablePath));

    // Act
    runSQL(insertTableQuery(tablePath));

    // Assert
    assertTableHasExpectedNumRows(tablePath, 3);

    // cleanup
    runSQL(dropTableQuery(tablePath));
  }

  @Test
  public void insertSelect() throws Exception {
    // Arrange
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);

    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createTableAsQuery(tablePath, 5));
    assertTableHasExpectedNumRows(tablePath, 5);

    // Act
    runSQL(insertSelectQuery(tablePath, 3));

    // Assert
    // Verify number of rows with select
    assertTableHasExpectedNumRows(tablePath, 8);

    // Cleanup
    runSQL(dropTableQuery(tablePath));

    assertCommitLogTail(
      String.format("CREATE TABLE %s", joinedTableKey(tablePath)),
      String.format("INSERT on TABLE %s", joinedTableKey(tablePath)),
      String.format("DROP TABLE %s", joinedTableKey(tablePath))
    );
  }

  @Test
  public void insertWithCommitSet() throws Exception {
    // Arrange
    String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(tablePath));

    String commitHashBranch = getCommitHashForBranch(DEFAULT_BRANCH_NAME);
    runSQL(useCommitQuery(commitHashBranch));

    // Act and Assert
    assertQueryThrowsExpectedError(insertTableQuery(tablePath),
      String.format("DDL and DML operations are only supported for branches - not on tags or commits. %s is not a branch.",
        ResolvedVersionContext.DETACHED_REF_NAME));
  }

  // Verify insert creates underlying iceberg files in the right locations
  @Test
  public void insertSelectVerifyFolders() throws Exception {
    // Arrange
    // Create a hierarchy of 2 folders to form key of TABLE
    final List<String> tablePath = Arrays.asList("if1", "if2", generateUniqueTableName());
    final String tableKey = joinedTableKey(tablePath);
    final String createTableQuery = String.format(
      "CREATE TABLE %s.%s %s",
      DATAPLANE_PLUGIN_NAME,
      tableKey,
      "(nation_key int, region_key int)");

    // Create empty
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createTableQuery);
    // Verify iceberg manifest/avro/metadata.json files on FS
    assertIcebergFilesExistAtSubPath(tablePath, 0, 1, 1, 0);

    // Do 2 separate Inserts so there are multiple data files.
    // Insert 1
    runSQL(insertSelectQuery(tablePath, 2));
    assertTableHasExpectedNumRows(tablePath, 2);
    // Verify iceberg manifest/avro/metadata.json files on FS
    assertIcebergFilesExistAtSubPath(tablePath, 1, 2, 2, 1);

    // Insert 2
    runSQL(insertSelectQuery(tablePath, 3));
    // Verify number of rows with select
    assertTableHasExpectedNumRows(tablePath, 5);

    // Assert
    // Verify iceberg manifest/avro/metadata.json files on FS
    assertIcebergFilesExistAtSubPath(tablePath, 2, 3, 3, 2);

    // Cleanup
    runSQL(dropTableQuery(tablePath));
  }

  @Test
  public void insertInDiffBranchesAndConflicts() throws Exception {
    // Arrange
    final String mainTableName = generateUniqueTableName();
    final List<String> mainTablePath = tablePathWithFolders(mainTableName);
    final String devBranchName = generateUniqueBranchName();

    // Set context to main
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    createFolders(mainTablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(mainTablePath));
    assertTableHasExpectedNumRows(mainTablePath, 0);

    // Create a dev branch from main
    runSQL(createBranchAtBranchQuery(devBranchName, DEFAULT_BRANCH_NAME));

    // insert into table on main branch
    runSQL(insertTableQuery(mainTablePath));
    assertTableHasExpectedNumRows(mainTablePath, 3);
    long mtime1 = getMtimeForTable(mainTablePath, new TableVersionContext(TableVersionType.BRANCH, DEFAULT_BRANCH_NAME), this);
    // switch to branch dev
    runSQL(useBranchQuery(devBranchName));

    // insert into table on dev branch so there will be conflicts
    runSQL(insertTableQuery(mainTablePath));
    assertTableHasExpectedNumRows(mainTablePath, 3);
    long mtime2 = getMtimeForTable(mainTablePath, new TableVersionContext(TableVersionType.BRANCH, devBranchName), this);
    // switch to branch dev
    // Act and Assert
    assertQueryThrowsExpectedError(mergeBranchQuery(devBranchName, DEFAULT_BRANCH_NAME),
      String.format(("VALIDATION ERROR: Merge branch %s into branch %s failed due to commit conflict on source %s"),
        devBranchName, DEFAULT_BRANCH_NAME, DATAPLANE_PLUGIN_NAME));
    assertThat(mtime2 > mtime1).isTrue();
    // Drop tables
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    runSQL(dropTableQuery(mainTablePath));
  }

  @Test
  public void insertInDiffBranchesAndMerge() throws Exception {
    // Arrange
    final List<String> shareFolderPath = Collections.singletonList(generateUniqueFolderName());
    final String mainTableName = generateUniqueTableName();
    final String devTableName = generateUniqueTableName();
    final List<String> mainTablePath = tablePathWithFolders(mainTableName);
    final List<String> devTablePath = tablePathWithFolders(devTableName);
    final String devBranchName = generateUniqueBranchName();

    // Creating an arbitrary commit to Nessie to make a common ancestor between two branches otherwise
    // those are un-related branches
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    runSQL(createFolderAtQueryWithIfNotExists(shareFolderPath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME)));

    // Create a dev branch from main
    runSQL(createBranchAtBranchQuery(devBranchName, DEFAULT_BRANCH_NAME));

    // Set context to main
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    createFolders(mainTablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(mainTablePath));
    assertTableHasExpectedNumRows(mainTablePath, 0);

    // Insert into table main
    runSQL(insertTableQuery(mainTablePath));
    assertTableHasExpectedNumRows(mainTablePath, 3);


    // switch to branch dev
    runSQL(useBranchQuery(devBranchName));
    // Check that table does not exist in Nessie in branch dev (since it was branched off before create table)
    assertQueryThrowsExpectedError(selectCountQuery(mainTablePath, DEFAULT_COUNT_COLUMN),
      String.format("VALIDATION ERROR: Object '%s' not found within '%s",
        mainTablePath.get(0),
        DATAPLANE_PLUGIN_NAME));
    createFolders(devTablePath, VersionContext.ofBranch(devBranchName));
    runSQL(createEmptyTableQuery(devTablePath));
    assertTableHasExpectedNumRows(devTablePath, 0);

    // Insert into table dev
    runSQL(insertTableQuery(devTablePath));
    assertTableHasExpectedNumRows(devTablePath, 3);

    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    // Check that dev table cannot be seen in branch main
    assertQueryThrowsExpectedError(selectCountQuery(devTablePath, DEFAULT_COUNT_COLUMN),
      String.format("VALIDATION ERROR: Object '%s' not found within '%s",
        devTablePath.get(0),
        DATAPLANE_PLUGIN_NAME));

    // Act
    runSQL(mergeBranchQuery(devBranchName, DEFAULT_BRANCH_NAME));

    // Assert and checking records in both tables
    // Table must now be visible in main.
    assertTableHasExpectedNumRows(devTablePath, 3);
    assertTableHasExpectedNumRows(mainTablePath, 3);

    // Drop tables
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    runSQL(dropTableQuery(mainTablePath));
    runSQL(dropTableQuery(devTablePath));
  }

  /**
   * Ctas in main branch
   * Insert in dev branch
   * Compare row counts in each branch
   * Merge branch to main branch and compare row count again
   */
  @Test
  public void insertAndCtasInDifferentBranches() throws Exception {
    // Arrange
    final String tableName = generateUniqueTableName();
    final String devBranch = generateUniqueBranchName();
    final List<String> tablePath = tablePathWithFolders(tableName);

    // Set context to main branch
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createTableAsQuery(tablePath, 5));
    // Verify with select
    assertTableHasExpectedNumRows(tablePath, 5);
    long mtime1 = getMtimeForTable(tablePath, new TableVersionContext(TableVersionType.BRANCH, DEFAULT_BRANCH_NAME), this);
    // Create dev branch
    runSQL(createBranchAtBranchQuery(devBranch, DEFAULT_BRANCH_NAME));
    // Switch to dev
    runSQL(useBranchQuery(devBranch));
    // Insert rows
    runSQL(insertSelectQuery(tablePath, 2));
    // Verify number of rows.
    assertTableHasExpectedNumRows(tablePath, 7);
    // Switch back to main
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    // Verify number of rows
    assertTableHasExpectedNumRows(tablePath, 5);

    // Act
    // Merge dev to main
    runSQL(mergeBranchQuery(devBranch, DEFAULT_BRANCH_NAME));
    long mtime2 = getMtimeForTable(tablePath, new TableVersionContext(TableVersionType.BRANCH, DEFAULT_BRANCH_NAME), this);
    // Assert
    assertTableHasExpectedNumRows(tablePath, 7);
    assertThat(mtime2 > mtime1).isTrue();
    // Cleanup
    runSQL(dropTableQuery(tablePath));
  }

  /**
   * The inserts should write data files relative to the table base location, and agnostic of the source configuration.
   * Create a table, insert some records
   * Create a different source with a dummy bucket path as root location
   * Make further inserts, operation should succeed
   * Verify the records
   */
  @Test
  public void insertAgnosticOfSourceBucket() throws Exception {
    // Arrange
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(tablePath));

    // Act
    runSQL(insertTableQuery(tablePath));
    runWithAlternateSourcePath(insertTableQuery(tablePath));

    // Assert rows from both inserts
    assertTableHasExpectedNumRows(tablePath, 6);
    assertAllFilesAreInBaseBucket(tablePath);

    // cleanup
    runSQL(dropTableQuery(tablePath));
  }

  @Test
  public void insertInDifferentTablesWithSameName() throws Exception {
    // Arrange
    final String tableName = generateUniqueTableName();
    final String devBranch = generateUniqueBranchName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    runSQL(createBranchAtBranchQuery(devBranch, DEFAULT_BRANCH_NAME));

    // Create table with this name in the main branch, insert records
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createTableAsQuery(tablePath, 5));
    runSQL(insertSelectQuery(tablePath,5));

    // Create table with this name in the dev branch, different source path, insert records
    runSQL(useBranchQuery(devBranch));
    createFolders(tablePath, VersionContext.ofBranch(devBranch));
    runWithAlternateSourcePath(createTableAsQuery(tablePath, 5));
    runSQL(insertSelectQuery(tablePath, 5));

    // Act: Assert the paths are correct in each branch
    assertAllFilesInAlternativeBucket(tablePath); // dev branch

    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    assertAllFilesAreInBaseBucket(tablePath);

    // cleanup
    runSQL(useBranchQuery(devBranch));
    runSQL(dropTableQuery(tablePath));

    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    runSQL(dropTableQuery(tablePath));
    runSQL(dropBranchForceQuery(devBranch));
  }

  @Test
  public void insertIntoUsingAtAndRef() throws Exception {
    // Arrange
    final String devBranch = "dev";
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(tablePath));

    runSQL(createBranchAtBranchQuery(devBranch, DEFAULT_BRANCH_NAME));
    runSQL(useBranchQuery(devBranch));

    // Act, insert value to the dev branch
    runSQL(insertTableAtQuery(tablePath, devBranch));
    runSQL(insertTableAtQueryWithRef(tablePath, devBranch));

    // Assert
    assertTableAtBranchHasExpectedNumRows(tablePath,devBranch, 6);
    assertTableAtBranchHasExpectedNumRows(tablePath,DEFAULT_BRANCH_NAME, 0);

    // cleanup
    runSQL(dropTableQuery(tablePath));
  }

  @Test
  public void testInsertWithAtAndUse() throws Exception {
    // Arrange
    final String devBranch = "dev";
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(tablePath));

    runSQL(createBranchAtBranchQuery(devBranch, DEFAULT_BRANCH_NAME));

    //Using main branch
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    // Act, insert value to the dev branch
    runSQL(insertTableAtQuery(tablePath, devBranch));

    // Assert
    assertTableAtBranchHasExpectedNumRows(tablePath,devBranch, 3);
    assertTableAtBranchHasExpectedNumRows(tablePath,DEFAULT_BRANCH_NAME, 0);

    // cleanup
    runSQL(dropTableQuery(tablePath));
  }

  @Test
  public void insertIntoNonExistentBranch() throws Exception {
    // Arrange
    final String nonExistentBranch = "nonExistentBranch";
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(tablePath));

    // Act, insert value to the nonExistentBranch branch. Should throw an error.
    assertQueryThrowsExpectedError(insertTableAtQuery(tablePath, nonExistentBranch), "does not exist");
  }

  @Test
  public void insertIntoUsingAtAndSelect() throws Exception {
    // Arrange
    final String devBranch = generateUniqueBranchName();
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = Collections.singletonList(tableName);

    runSQL(createBranchAtBranchQuery(devBranch, DEFAULT_BRANCH_NAME));
    runSQL(createEmptyTableQuery(tablePath));
    runSQL(useBranchQuery(devBranch));
    runSQL(createEmptyTableQueryWithAt(tablePath, devBranch));

    // Act, insert value to the default branch and then devBranch
    runSQL(insertTableAtQuery(tablePath, DEFAULT_BRANCH_NAME));
    runSQL(insertTableAtQueryWithSelect(tablePath, devBranch, tablePath, "BRANCH main"));

    // Assert
    assertTableAtBranchHasExpectedNumRows(tablePath, devBranch, 3);
    assertTableAtBranchHasExpectedNumRows(tablePath, DEFAULT_BRANCH_NAME, 3);

    // cleanup
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    runSQL(dropTableQuery(tablePath));
    runSQL(dropTableQueryWithAt(tablePath, devBranch));
  }

  @Test
  public void insertIntoWithTags() throws Exception {
    // Arrange
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = Collections.singletonList(tableName);
    final String beforeInsertTag = generateUniqueTagName();
    final String afterInsertTag = generateUniqueTagName();

    //Act, create tag before insert to table
    runSQL(createEmptyTableQuery(tablePath));
    runSQL(createTagQueryWithFrom(beforeInsertTag, DEFAULT_BRANCH_NAME));

    //Act, create tag after insert to table
    runSQL(insertTableAtQuery(tablePath, DEFAULT_BRANCH_NAME));
    runSQL(createTagQueryWithFrom(afterInsertTag, DEFAULT_BRANCH_NAME));

    // Assert
    runSQL(useTagQuery(beforeInsertTag));
    assertTableHasExpectedNumRows(tablePath, 0);
    runSQL(useTagQuery(afterInsertTag));
    assertTableHasExpectedNumRows(tablePath, 3);

    // cleanup
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    runSQL(dropTableQuery(tablePath));
    runSQL(dropTagQuery(beforeInsertTag));
    runSQL(dropTagQuery(afterInsertTag));
  }

  @Test
  public void insertIntoUsingAtTableOnlyExistsInDevBranch() throws Exception {
    // Create branch
    final String devBranch = "dev";
    runSQL(createBranchAtBranchQuery(devBranch, DEFAULT_BRANCH_NAME));

    // we only have table in dev branch
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(devBranch));
    runSQL(createEmptyTableQueryWithAt(tablePath, devBranch));
    runSQL(useBranchQuery(devBranch));

    // Act, insert value to the dev branch
    runSQL(insertTableAtQuery(tablePath, devBranch));

    // Assert
    assertTableAtBranchHasExpectedNumRows(tablePath,devBranch, 3);

    // cleanup
    runSQL(dropTableQuery(tablePath));
  }

  @Test
  public void insertIntoAtTableOnlyExistsInDevBranch() throws Exception {
    // Create branch
    final String devBranch = "dev";
    runSQL(createBranchAtBranchQuery(devBranch, DEFAULT_BRANCH_NAME));

    // we only have table in dev branch
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = tablePathWithFolders(tableName);
    createFolders(tablePath, VersionContext.ofBranch(devBranch));
    runSQL(createEmptyTableQueryWithAt(tablePath, devBranch));

    // Act, insert value to the dev branch (this time, we are not using USE BRANCH ...)
    runSQL(insertTableAtQuery(tablePath, devBranch));

    // Assert
    assertTableAtBranchHasExpectedNumRows(tablePath,devBranch, 3);

    // cleanup
    runSQL(dropTableQueryWithAt(tablePath, devBranch));
  }
}
