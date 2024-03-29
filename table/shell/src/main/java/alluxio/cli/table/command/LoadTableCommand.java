/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.cli.table.command;

import alluxio.AlluxioURI;
import alluxio.annotation.PublicApi;
import alluxio.cli.CommandUtils;
import alluxio.cli.fs.FileSystemShellUtils;
import alluxio.cli.fs.command.AbstractDistributedJobCommand;
import alluxio.cli.fs.command.DistributedLoadUtils;
import alluxio.client.file.FileSystemContext;
import alluxio.client.file.URIStatus;
import alluxio.client.table.TableMasterClient;
import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AlluxioException;
import alluxio.exception.status.AlluxioStatusException;
import alluxio.exception.status.InvalidArgumentException;
import alluxio.table.common.CatalogPathUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Loads table into Alluxio space, makes it resident in memory.
 */
@ThreadSafe
@PublicApi
public class LoadTableCommand extends AbstractTableCommand {
  private static final Option REPLICATION_OPTION =
      Option.builder()
          .longOpt("replication")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .type(Number.class)
          .argName("replicas")
          .desc("Number of block replicas of each loaded file, "
              + "default: alluxio.table.load.default.replication")
          .build();
  private static final Option ACTIVE_JOB_COUNT_OPTION =
      Option.builder()
          .longOpt("active-jobs")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .type(Number.class)
          .argName("active job count")
          .desc("Number of active jobs that can run at the same time. Later jobs must wait. "
              + "The default upper limit is "
              + AbstractDistributedJobCommand.DEFAULT_ACTIVE_JOBS)
          .build();
  private static final Option HOSTS_OPTION =
      Option.builder()
          .longOpt("hosts")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("hosts")
          .desc("A list of worker hosts separated by comma."
              + " When host and locality options are not set,"
              + " all hosts will be selected unless explicitly excluded by setting excluded option"
              + "('excluded-hosts', 'excluded-host-file', 'excluded-locality'"
              + " and 'excluded-locality-file')."
              + " Only one of the 'hosts' and 'host-file' should be set,"
              + " and it should not be set with excluded option together.")
          .build();
  private static final Option HOST_FILE_OPTION =
      Option.builder()
          .longOpt("host-file")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("host-file")
          .desc("Host File contains worker hosts, each line has a worker host."
              + " When host and locality options are not set,"
              + " all hosts will be selected unless explicitly excluded by setting excluded option"
              + "('excluded-hosts', 'excluded-host-file', 'excluded-locality'"
              + " and 'excluded-locality-file')."
              + " Only one of the 'hosts' and 'host-file' should be set,"
              + " and it should not be set with excluded option together.")
          .build();
  private static final Option EXCLUDED_HOSTS_OPTION =
      Option.builder()
          .longOpt("excluded-hosts")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("excluded-hosts")
          .desc("A list of excluded worker hosts separated by comma."
              + " Only one of the 'excluded-hosts' and 'excluded-host-file' should be set,"
              + " and it should not be set with 'hosts', 'host-file', 'locality'"
              + " and 'locality-file' together.")
          .build();
  private static final Option EXCLUDED_HOST_FILE_OPTION =
      Option.builder()
          .longOpt("excluded-host-file")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("excluded-host-file")
          .desc("Host File contains excluded worker hosts, each line has a worker host."
              + " Only one of the 'excluded-hosts' and 'excluded-host-file' should be set,"
              + " and it should not be set with 'hosts', 'host-file', 'locality'"
              + " and 'locality-file' together.")
          .build();
  private static final Option LOCALITY_OPTION =
      Option.builder()
          .longOpt("locality")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("locality")
          .desc("A list of worker locality separated by comma."
              + " When host and locality options are not set,"
              + " all hosts will be selected unless explicitly excluded by setting excluded option"
              + "('excluded-hosts', 'excluded-host-file', 'excluded-locality'"
              + " and 'excluded-locality-file')."
              + " Only one of the 'locality' and 'locality-file' should be set,"
              + " and it should not be set with excluded option together.")
          .build();
  private static final Option LOCALITY_FILE_OPTION =
      Option.builder()
          .longOpt("locality-file")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("locality-file")
          .argName("locality-file")
          .desc("Locality File contains worker localities, each line has a worker locality."
              + " When host and locality options are not set,"
              + " all hosts will be selected unless explicitly excluded by setting excluded option"
              + "('excluded-hosts', 'excluded-host-file', 'excluded-locality'"
              + " and 'excluded-locality-file')."
              + " Only one of the 'locality' and 'locality-file' should be set,"
              + " and it should not be set with excluded option together.")
          .build();
  private static final Option EXCLUDED_LOCALITY_OPTION =
      Option.builder()
          .longOpt("excluded-locality")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("excluded-locality")
          .desc("A list of excluded worker locality separated by comma."
              + " Only one of the 'excluded-locality' and 'excluded-locality-file' should be set,"
              + " and it should not be set with 'hosts', 'host-file', 'locality'"
              + " and 'locality-file' together.")
          .build();
  private static final Option EXCLUDED_LOCALITY_FILE_OPTION =
      Option.builder()
          .longOpt("excluded-locality-file")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .argName("excluded-locality-file")
          .desc("Locality File contains excluded worker localities,"
              + " each line has a worker locality."
              + " Only one of the 'excluded-locality' and 'excluded-locality-file' should be set,"
              + " and it should not be set with 'hosts', 'host-file', 'locality'"
              + " and 'locality-file' together.")
          .build();
  private static final Option BATCH_SIZE_OPTION =
      Option.builder()
          .longOpt("batch-size")
          .required(false)
          .hasArg(true)
          .numberOfArgs(1)
          .type(Number.class)
          .argName("batch-size")
          .desc("Number of files per request")
          .build();
  private static final Option PASSIVE_CACHE_OPTION =
      Option.builder()
          .longOpt("passive-cache")
          .required(false)
          .hasArg(false)
          .desc("Use passive-cache or direct cache request,"
              + " turn on if you want to use the old passive cache implementation")
          .build();

  /**
   *  Constructs a new instance to load table into Alluxio space.
   *
   * @param conf      the alluxio configuration
   * @param client    the client interface which can be used to make RPCs against the table master
   * @param fsContext the filesystem of Alluxio
   */
  public LoadTableCommand(AlluxioConfiguration conf, TableMasterClient client,
      FileSystemContext fsContext) {
    super(conf, client, fsContext);
  }

  @Override
  public String getUsage() {
    return "load [--replication <num>] [--active-jobs <num>] [--batch-size <num>] "
        + "[--hosts <host1>,<host2>,...,<hostN>] [--host-file <hostFilePath>] "
        + "[--excluded-hosts <host1>,<host2>,...,<hostN>] [--excluded-host-file <hostFilePath>] "
        + "[--locality <locality1>,<locality2>,...,<localityN>] "
        + "[--locality-file <localityFilePath>] "
        + "[--excluded-locality <locality1>,<locality2>,...,<localityN>] "
        + "[--excluded-locality-file <localityFilePath>] "
        + "[--passive-cache] "
        + "<db name> <table name>";
  }

  @Override
  public String getCommandName() {
    return "load";
  }

  @Override
  public String getDescription() {
    return "Loads table into Alluxio space. Currently only support hive table.";
  }

  @Override
  public Options getOptions() {
    return new Options()
        .addOption(ACTIVE_JOB_COUNT_OPTION)
        .addOption(BATCH_SIZE_OPTION)
        .addOption(HOSTS_OPTION)
        .addOption(HOST_FILE_OPTION)
        .addOption(REPLICATION_OPTION)
        .addOption(PASSIVE_CACHE_OPTION);
  }

  @Override
  public void validateArgs(CommandLine cl) throws InvalidArgumentException {
    CommandUtils.checkNumOfArgsEquals(this, cl, 2);
  }

  @Override
  public int run(CommandLine cl) throws AlluxioException, IOException {
    System.out.println("***Tips：Load table command only support hive table for now.***");
    String[] args = cl.getArgs();
    String dbName = args[0];
    String tableName = args[1];
    if (!tableExists(dbName, tableName)) {
      System.out.printf("Failed to load table %s.%s: table is not exit.%n", dbName, tableName);
      return 0;
    }
    mActiveJobs = FileSystemShellUtils.getIntArg(cl, ACTIVE_JOB_COUNT_OPTION,
        AbstractDistributedJobCommand.DEFAULT_ACTIVE_JOBS);
    System.out.format("Allow up to %s active jobs%n", mActiveJobs);
    AlluxioConfiguration conf = mFsContext.getClusterConf();
    int defaultBatchSize = conf.getInt(PropertyKey.JOB_REQUEST_BATCH_SIZE);
    int defaultReplication = conf.getInt(PropertyKey.TABLE_LOAD_DEFAULT_REPLICATION);
    int replication = FileSystemShellUtils.getIntArg(cl, REPLICATION_OPTION, defaultReplication);
    int batchSize = FileSystemShellUtils.getIntArg(cl, BATCH_SIZE_OPTION, defaultBatchSize);
    boolean directCache = !cl.hasOption(PASSIVE_CACHE_OPTION.getLongOpt());
    Set<String> workerSet = new HashSet<>();
    Set<String> excludedWorkerSet = new HashSet<>();
    Set<String> localityIds = new HashSet<>();
    Set<String> excludedLocalityIds = new HashSet<>();
    if (cl.hasOption(HOST_FILE_OPTION.getLongOpt())) {
      String hostFile = cl.getOptionValue(HOST_FILE_OPTION.getLongOpt()).trim();
      readLinesToSet(workerSet, hostFile);
    } else if (cl.hasOption(HOSTS_OPTION.getLongOpt())) {
      String argOption = cl.getOptionValue(HOSTS_OPTION.getLongOpt()).trim();
      readItemsFromOptionString(workerSet, argOption);
    }
    if (cl.hasOption(EXCLUDED_HOST_FILE_OPTION.getLongOpt())) {
      String hostFile = cl.getOptionValue(EXCLUDED_HOST_FILE_OPTION.getLongOpt()).trim();
      readLinesToSet(excludedWorkerSet, hostFile);
    } else if (cl.hasOption(EXCLUDED_HOSTS_OPTION.getLongOpt())) {
      String argOption = cl.getOptionValue(EXCLUDED_HOSTS_OPTION.getLongOpt()).trim();
      readItemsFromOptionString(excludedWorkerSet, argOption);
    }
    if (cl.hasOption(LOCALITY_FILE_OPTION.getLongOpt())) {
      String localityFile = cl.getOptionValue(LOCALITY_FILE_OPTION.getLongOpt()).trim();
      readLinesToSet(localityIds, localityFile);
    } else if (cl.hasOption(LOCALITY_OPTION.getLongOpt())) {
      String argOption = cl.getOptionValue(LOCALITY_OPTION.getLongOpt()).trim();
      readItemsFromOptionString(localityIds, argOption);
    }
    if (cl.hasOption(EXCLUDED_LOCALITY_FILE_OPTION.getLongOpt())) {
      String localityFile = cl.getOptionValue(EXCLUDED_LOCALITY_FILE_OPTION.getLongOpt()).trim();
      readLinesToSet(excludedLocalityIds, localityFile);
    } else if (cl.hasOption(EXCLUDED_LOCALITY_OPTION.getLongOpt())) {
      String argOption = cl.getOptionValue(EXCLUDED_LOCALITY_OPTION.getLongOpt()).trim();
      readItemsFromOptionString(excludedLocalityIds, argOption);
    }
    List<URIStatus> filePool = new ArrayList<>(batchSize);
    // Only support hive table for now.
    String udbType = "hive";
    // To load table into Alluxio space, we get the SDS table's Alluxio parent path first and
    // then load data under the path. For now, each table have one single parent path generated
    // by CatalogPathUtils#getTablePathUdb.
    // The parent path is mounted by SDS, it's mapping of Utable's UFS path in Alluxio.
    // e.g.
    //                                                   attached
    // [SDS]default.test                                 <===== [hive]default.test
    //                                                    mount
    // [SDS]alluxio:///catalog/default/tables/test/hive/ <===== [hive]hdfs:///.../default.db/test/
    // PLEASE NOTE: If Alluxio support different parent path, this statement can not guaranteed
    // to be correct.
    AlluxioURI path = CatalogPathUtils.getTablePathUdb(dbName, tableName, udbType);
    System.out.printf("Loading table %s.%s...%n", dbName, tableName);
    DistributedLoadUtils.distributedLoad(this, filePool, batchSize, path, replication, workerSet,
        excludedWorkerSet, localityIds, excludedLocalityIds, directCache, true);
    System.out.println("Done");
    return 0;
  }

  private void readItemsFromOptionString(Set<String> localityIds, String argOption) {
    for (String locality : StringUtils.split(argOption, ",")) {
      locality = locality.trim().toUpperCase();
      if (!locality.isEmpty()) {
        localityIds.add(locality);
      }
    }
  }

  private void readLinesToSet(Set<String> workerSet, String hostFile) throws IOException {
    try (BufferedReader reader = new BufferedReader(new FileReader(hostFile))) {
      for (String worker; (worker = reader.readLine()) != null;) {
        worker = worker.trim().toUpperCase();
        if (!worker.isEmpty()) {
          workerSet.add(worker);
        }
      }
    }
  }

  private boolean tableExists(String dbName, String tableName) {
    try {
      // If getTable method called succeed, the table is exists.
      mClient.getTable(dbName, tableName);
      return true;
    } catch (AlluxioStatusException e) {
      return false;
    }
  }
}
