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
import { useMemo } from "react";
import { connect } from "react-redux";
import { withRouter } from "react-router";
import { compose } from "redux";

import LinkWithRef from "@app/components/LinkWithRef/LinkWithRef";
import RealTimeTimer from "@app/components/RealTimeTimer";
import SampleDataMessage from "@app/pages/ExplorePage/components/SampleDataMessage";
import jobsUtils from "@app/utils/jobsUtils";
import {
  getExploreState,
  getImmutableTable,
  getJobProgress,
  getRunStatus,
} from "@app/selectors/explore";
import { getJobList } from "@app/selectors/jobs";
import { intl } from "@app/utils/intl";
// @ts-ignore
import { Tooltip } from "dremio-ui-lib";
import * as jobPaths from "dremio-ui-common/paths/jobs.js";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";

import "./ExploreTableJobStatus.less";

export const JOB_STATUS = {
  notSubmitted: "NOT_SUBMITTED",
  starting: "STARTING",
  running: "RUNNING",
  completed: "COMPLETED",
  canceled: "CANCELED",
  failed: "FAILED",
  cancellationRequested: "CANCELLATION_REQUESTED",
  enqueued: "ENQUEUED",
  pending: "PENDING",
  planning: "PLANNING",
  metadataRetrieval: "METADATA_RETRIEVAL",
  engineStart: "ENGINE_START",
  queued: "QUEUED",
  executionPlanning: "EXECUTION_PLANNING",
};

export const isWorking = (status: string) => {
  return [
    JOB_STATUS.starting,
    JOB_STATUS.enqueued,
    JOB_STATUS.running,
    JOB_STATUS.cancellationRequested,
    JOB_STATUS.pending,
    JOB_STATUS.metadataRetrieval,
    JOB_STATUS.planning,
    JOB_STATUS.engineStart,
    JOB_STATUS.queued,
    JOB_STATUS.executionPlanning,
  ].includes(status);
};

const { formatMessage } = intl;
const jobStatusNames = {
  [JOB_STATUS.notSubmitted]: formatMessage({
    id: "JobStatus.NotSubmitted",
  }),
  [JOB_STATUS.starting]: formatMessage({ id: "JobStatus.Starting" }),
  [JOB_STATUS.running]: formatMessage({ id: "JobStatus.Running" }),
  [JOB_STATUS.completed]: formatMessage({ id: "JobStatus.Completed" }),
  [JOB_STATUS.canceled]: formatMessage({ id: "JobStatus.Canceled" }),
  [JOB_STATUS.failed]: formatMessage({ id: "JobStatus.Failed" }),
  [JOB_STATUS.cancellationRequested]: formatMessage({
    id: "JobStatus.CancellationRequested",
  }),
  [JOB_STATUS.enqueued]: formatMessage({ id: "JobStatus.Enqueued" }),
  [JOB_STATUS.pending]: formatMessage({ id: "JobStatus.Pending" }),
  [JOB_STATUS.metadataRetrieval]: formatMessage({
    id: "JobStatus.MetadataRetrieval",
  }),
  [JOB_STATUS.planning]: formatMessage({ id: "JobStatus.Planning" }),
  [JOB_STATUS.engineStart]: formatMessage({
    id: "JobStatus.EngineStart",
  }),
  [JOB_STATUS.queued]: formatMessage({ id: "JobStatus.Queued" }),
  [JOB_STATUS.executionPlanning]: formatMessage({
    id: "JobStatus.ExecutionPlanning",
  }),
};

type ExploreTableJobStatusProps = {
  approximate: boolean;
  //connected
  jobDetails: any;
  jobProgress: any;
  jobId: string;
  haveRows: boolean;
  version: string;
  jobAttempts: number;
  runStatus: string;
};

const ExploreTableJobStatus = (props: ExploreTableJobStatusProps) => {
  const {
    jobDetails,
    jobProgress,
    jobId,
    jobAttempts,
    approximate,
    haveRows,
    runStatus,
  } = props;
  const projectId = getSonarContext()?.getSelectedProjectId?.();
  const isComplete = jobProgress?.status === JOB_STATUS.completed;
  const jobProgressStatus = runStatus
    ? formatMessage({ id: "Explore.Run" })
    : formatMessage({ id: "Explore.Preview" });
  const jobType =
    jobDetails?.queryType === "UI_RUN"
      ? formatMessage({ id: "Explore.Run" })
      : jobDetails?.queryType === "UI_PREVIEW"
      ? formatMessage({ id: "Explore.Preview" })
      : jobProgressStatus;

  const showResultsWarning = useMemo(() => {
    return isComplete && jobDetails?.stats?.isOutputLimited;
  }, [isComplete, jobDetails?.stats?.isOutputLimited]);

  const jobStatus = {
    label: formatMessage({
      id: `Explore.${isComplete ? "Rows" : "Status"}`,
    }),
    value: isComplete
      ? !jobDetails?.outputRecords
        ? "-"
        : showResultsWarning
        ? jobDetails.outputRecords > 100000
          ? ">100K"
          : "<100K"
        : jobDetails?.outputRecords?.toLocaleString() ?? "-"
      : jobStatusNames[jobProgress?.status],
  };

  const renderTime = () => {
    if (!jobProgress) return null;
    // if not complete - show timer, else format end-start
    if (isWorking(jobProgress.status)) {
      return (
        <RealTimeTimer
          startTime={jobProgress.startTime}
          formatter={jobsUtils.formatJobDuration}
        />
      );
    } else if (jobDetails?.startTime && jobDetails?.endTime) {
      return jobsUtils.formatJobDuration(
        jobDetails.endTime - jobDetails.startTime
      );
    } else {
      return null;
    }
  };

  if (jobProgress === null) {
    if (approximate && haveRows) {
      return <SampleDataMessage />;
    } else return null;
  } else {
    return (
      <div className="exploreJobStatus">
        <div className="exploreJobStatus__item">
          <span style={styles.label}>
            {formatMessage({ id: "Explore.Job" })}:{" "}
          </span>

          {jobId && (
            <Tooltip title={`Jobs Detail Page for #${jobId}`}>
              <LinkWithRef
                target="_blank"
                rel="noreferrer noopener"
                to={{
                  pathname: jobPaths.job.link({ jobId, projectId }),
                  query: {
                    attempts: jobAttempts,
                  },
                  state: {
                    isFromJobListing: false,
                  },
                }}
              >
                {jobType}
              </LinkWithRef>
            </Tooltip>
          )}
        </div>

        <div className="exploreJobStatus__item">
          <span style={styles.label}>{jobStatus.label}: </span>
          {showResultsWarning ? (
            <Tooltip
              placement="top"
              title={
                <>
                  <div className="exploreJobStatus__limitWarning__title">
                    {formatMessage({ id: "Explore.Run.NewWarning.Header" })}
                  </div>
                  <p>
                    {formatMessage(
                      {
                        id: "Explore.Run.NewWarning",
                      },
                      {
                        rows:
                          jobDetails?.outputRecords?.toLocaleString() ?? "-",
                      }
                    )}
                  </p>
                </>
              }
            >
              <span className="exploreJobStatus__limitWarning">
                {jobStatus.value}
                {jobStatus.value !== "-" && (
                  <dremio-icon name="interface/warning" alt="output limited" />
                )}
              </span>
            </Tooltip>
          ) : (
            jobStatus.value
          )}
        </div>

        <div className="exploreJobStatus__item">{renderTime()}</div>
      </div>
    );
  }
};

function mapStateToProps(
  state: Record<string, any>,
  props: ExploreTableJobStatusProps
) {
  const { approximate, version } = props;
  const explorePageState = getExploreState(state);
  const queryStatuses = explorePageState.view.queryStatuses;
  const queryTabNumber = explorePageState.view.queryTabNumber;
  const jobId = queryStatuses[queryTabNumber - 1]?.jobId;
  const jobProgress = getJobProgress(state, version);

  const jobList = getJobList(state);
  const selectedJob = jobList.find((job: any) => job.get("id") === jobId);
  let jobAttempts = 1;
  if (selectedJob && selectedJob.get("totalAttempts")) {
    jobAttempts = selectedJob.get("totalAttempts");
  } else if (selectedJob && selectedJob.get("attemptDetails")) {
    jobAttempts = selectedJob.get("attemptDetails").size;
  }
  const runStatus = getRunStatus(state).isRun;

  let haveRows = false;
  // get preview tableData for preview w/o jobProgress
  if (!jobProgress && approximate) {
    const tableData = getImmutableTable(state, version);
    const rows = tableData.get("rows");
    haveRows = rows && !!rows.size;
  }

  return {
    haveRows,
    jobProgress,
    jobId,
    jobAttempts,
    runStatus,
  };
}

export default compose(
  connect(mapStateToProps),
  withRouter
)(ExploreTableJobStatus);

export const styles = {
  label: {
    display: "inline-box",
    paddingRight: 3,
    fontWeight: 500,
  },
};
