#!/bin/bash
# Used to set the deployment namespace and year. Reads variables from deployment-config.sh

source ./deployment-config.sh

if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -i '' "s|export const year: number = .*;|export const year: number = $DEPLOYMENT_YEAR;|g" ./webapp/src/main/webui/src/app/app.config.ts
else
  sed -i "s|export const year: number = .*;|export const year: number = $DEPLOYMENT_YEAR;|g" ./webapp/src/main/webui/src/app/app.config.ts
fi
sed -i '' "s|export const namespace: string = \".*\";|export const namespace: string = \"$DEPLOYMENT_NAMESPACE\";|g" ./webapp/src/main/webui/src/app/app.config.ts
sed -i '' "s|\"baseHref\": \"/[0-9]\{4\}/\"|\"baseHref\": \"/$DEPLOYMENT_YEAR/\"|g" ./webapp/src/main/webui/angular.json
sed -i '' "s|export DEPLOYMENT_YEAR=[0-9]\{4\}|export DEPLOYMENT_YEAR=$DEPLOYMENT_YEAR|g" ./deployment-config.sh

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
