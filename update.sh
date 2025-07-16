#!/bin/bash

set -e
set -o pipefail
set -x


START_DIR="$(pwd)"

sudo docker ps
source ./set-deployment-config.sh
source ./set-deployment-target.sh

if [ "$DEPLOYMENT_NAMESPACE" == "us/congress" ]; then
	USC_DIR="$START_DIR/../../congress"
	
	cd "$USC_DIR"
	git pull
	python3 -m venv env
	source env/bin/activate
	pip install .
	
	CONGRESS=$(( (DEPLOYMENT_YEAR - 1789) / 2 + 1 ))
	
	usc-run govinfo --bulkdata=BILLSTATUS --congress=$CONGRESS
	usc-run bills --govtrack --congress=$CONGRESS
	usc-run votes --congress=$CONGRESS
	
	cd "$START_DIR"
fi

mvn install

# Update USC Legislator files
#curl -sSfL https://unitedstates.github.io/congress-legislators/legislators-current.json -o databuilder/src/main/resources/legislators-current.json
#curl -sSfL https://unitedstates.github.io/congress-legislators/legislators-historical.json -o databuilder/src/main/resources/legislators-historical.json


cd databuilder
#mvn exec:java -Dquarkus.devservices.enabled=false -Dquarkus.launch.devmode=false -Dvertx.options.warningExceptionTime=-1 -Dtest-containers.disabled=true

mvn exec:java \
  -Dquarkus.devservices.enabled=false \
  -Dquarkus.launch.devmode=false \
  -Dvertx.options.warningExceptionTime=-1 \
  -Dtest-containers.disabled=true \
2>&1



#mvn package -Dquarkus.package.type=uber-jar
#java -jar target/databuilder-0.0.1-SNAPSHOT-runner.jar

cd ..

./deploy.sh
