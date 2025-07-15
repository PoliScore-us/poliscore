#!/bin/bash
# Used to set the deployment namespace and year. Reads variables from deployment-config.sh

source ./deployment-config.sh

sed -i '' "s|export const year: number = [0-9]\+;|export const year: number = $DEPLOYMENT_YEAR;|g" ./webapp/src/main/webui/src/app/app.config.ts
sed -i '' "s|\"baseHref\": \"/[0-9]\{4\}/\"|\"baseHref\": \"/$DEPLOYMENT_YEAR/\"|g" ./webapp/src/main/webui/angular.json
sed -i '' "s|export DEPLOYMENT_YEAR=[0-9]\{4\}|export DEPLOYMENT_YEAR=$DEPLOYMENT_YEAR|g" ./deployment-config.sh
echo "MANUAL STEP : Make sure this year is in the list of SUPPORTED_DEPLOYMENTS in PoliScoreConfigService"
echo "MANUAL STEP : Don't forget to change the deployment year in the cloudfront routing script"
echo "MANUAL STEP : Make sure to update the list of supported congresses in the front-end"

update_property() {
  local file=$1
  local key=$2
  local value=$3

  # Create the file if it doesn't exist
  if [ ! -f "$file" ]; then
    mkdir -p "$(dirname "$file")"
    touch "$file"
  fi

  # Remove any existing line for this key
  sed -i '' "/^$key\s*=/d" "$file"

  # Append new value
  echo "$key=$value" >> "$file"
}

# Target files
DB_PROPS="./databuilder/src/main/resources/application.properties"
WEB_PROPS="./webapp/src/main/resources/application.properties"

# Update or create properties
update_property "$DB_PROPS" "deployment.year" "$DEPLOYMENT_YEAR"
update_property "$DB_PROPS" "deployment.namespace" "$DEPLOYMENT_NAMESPACE"
update_property "$WEB_PROPS" "deployment.year" "$DEPLOYMENT_YEAR"
update_property "$WEB_PROPS" "deployment.namespace" "$DEPLOYMENT_NAMESPACE"
