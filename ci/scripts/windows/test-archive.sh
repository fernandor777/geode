#!/usr/local/bin/tini-wrapper /bin/bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e -x

export TERM=${TERM:-dumb}
export BUILDROOT=$(pwd)
export DEST_DIR=${BUILDROOT}/built-geode
export GRADLE_TASK=${1}
export SANITIZED_GRADLE_TASK=${GRADLE_TASK##*:}
export BASE_FILENAME=${2}
export TMPDIR=${DEST_DIR}/tmp
export GEODE_BUILD=${DEST_DIR}/test
export GEODE_BUILD_VERSION_NUMBER=$(grep "versionNumber *=" ${GEODE_BUILD}/gradle.properties | awk -F "=" '{print $2}' | tr -d ' ')
export BUILD_TIMESTAMP=$(date +%s)
GEODE_PULL_REQUEST_ID_FILE=${BUILDROOT}/geode/.git/id
if [ -e "${GEODE_PULL_REQUEST_ID_FILE}" ]; then
  GEODE_PULL_REQUEST_ID=$(cat ${GEODE_PULL_REQUEST_ID_FILE})
fi

GCLOUD=gcloud
GSUTIL=gsutil
UNAME_O=$(uname -o)
# Check for Windows (MINGW64) environment
if [ ${UNAME_O} = "Msys" ]; then
  GCLOUD=gcloud.cmd
  GSUTIL=gsutil.cmd
fi


GEODE_BUILD_VERSION_FILE=${BUILDROOT}/geode-build-version/number

if [ ! -e "${GEODE_BUILD_VERSION_FILE}" ] && [ -z "${GEODE_PULL_REQUEST_ID}" ]; then
  echo "${GEODE_BUILD_VERSION_FILE} file does not exist. Concourse is probably not configured correctly."
  exit 1
fi
if [ -z ${MAINTENANCE_VERSION+x} ]; then
  echo "MAINTENANCE_VERSION is unset. Check your pipeline configuration and make sure this script is called properly."
  exit 1
fi


if [ -z "${GEODE_PULL_REQUEST_ID}" ]; then
CONCOURSE_VERSION=$(cat ${GEODE_BUILD_VERSION_FILE})
  CONCOURSE_PRODUCT_VERSION=${CONCOURSE_VERSION%%-*}
  GEODE_PRODUCT_VERSION=${GEODE_BUILD_VERSION_NUMBER}
  CONCOURSE_BUILD_SLUG=${CONCOURSE_VERSION##*-}
  BUILD_ID=${CONCOURSE_VERSION##*.}
  FULL_PRODUCT_VERSION=${GEODE_PRODUCT_VERSION}-${CONCOURSE_BUILD_SLUG}

  echo "Concourse VERSION is ${CONCOURSE_VERSION}"
  echo "Geode product VERSION is ${GEODE_PRODUCT_VERSION}"
  echo "Build ID is ${BUILD_ID}"
else
  FULL_PRODUCT_VERSION="geode-pr-${GEODE_PULL_REQUEST_ID}"
fi


directories_file=${DEST_DIR}/artifact_directories
mkdir -p ${TMPDIR}

echo "TMPDIR = ${TMPDIR}"
echo "GRADLE_TASK = ${GRADLE_TASK}"
echo "BASE_FILENAME = ${BASE_FILENAME}"

set +e
$GCLOUD info
$GCLOUD config set account ${SERVICE_ACCOUNT}

$GSUTIL ls gs://files.apachegeode-ci.info/
set -e

export FILENAME=${BASE_FILENAME}-${FULL_PRODUCT_VERSION}.tgz

pushd ${GEODE_BUILD}

  set +e
  ./gradlew combineReports
  find . -type d -name "reports" > ${directories_file}
  find . -type d -name "test-results" >> ${directories_file}
  (find . -type d -name "*Test" | grep "build/[^/]*Test$") >> ${directories_file}
  find . -name "*-progress*txt" >> ${directories_file}
  find . -type d -name "callstacks" >> ${directories_file}
  echo "Collecting the following artifacts..."
  cat ${directories_file}
  echo ""
  tar zcf ${DEST_DIR}/${FILENAME} -T ${directories_file}
popd

ARTIFACTS_DESTINATION="${ARTIFACT_BUCKET}/builds/${FULL_PRODUCT_VERSION}"
TEST_RESULTS_DESTINATION="${ARTIFACTS_DESTINATION}/test-results/${SANITIZED_GRADLE_TASK}/${BUILD_TIMESTAMP}/"
TEST_ARTIFACTS_DESTINATION="${ARTIFACTS_DESTINATION}/test-artifacts/${BUILD_TIMESTAMP}/"


if [ ! -d "${GEODE_BUILD}/build/reports/combined" ]; then
    echo "No tests exist, compile failed."
    mkdir -p ${GEODE_BUILD}/build/reports/combined
    echo "<html><head><title>No Test Results Were Captured</title></head><body><h1>No Test Results Were Captured</h1></body></html>" > ${GEODE_BUILD}/build/reports/combined/index.html
fi

pushd ${GEODE_BUILD}/build/reports/combined
$GSUTIL -q -m cp -r * gs://${TEST_RESULTS_DESTINATION}
popd

echo ""
printf "\033[92m=-=-=-=-=-=-=-=-=-=-=-=-=-=  Test Results Website =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=\033[0m\n"
printf "\033[92mhttp://${TEST_RESULTS_DESTINATION}\033[0m\n"
printf "\033[92m=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=\033[0m\n"
printf "\n"

$GSUTIL cp ${DEST_DIR}/${FILENAME} gs://${TEST_ARTIFACTS_DESTINATION}

printf "\033[92mTest artifacts from this job are available at:\033[0m\n"
printf "\n"
printf "\033[92mhttp://${TEST_ARTIFACTS_DESTINATION}${FILENAME}\033[0m\n"
