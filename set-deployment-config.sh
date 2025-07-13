#!/bin/bash
# Used to set the deployment namespace and year

CURRENT_YEAR=$1
NEXT_YEAR=$2
PREVIOUS_CONGRESS=$(( (CURRENT_YEAR - 1789) / 2 + 1))
NEXT_CONGRESS=$(( (NEXT_YEAR - 1789) / 2 + 1 ))

sed -i '' "s|DEPLOYMENT_YEAR = \"$CURRENT_YEAR\";|DEPLOYMENT_YEAR = \"$NEXT_YEAR\";|g" ./core/src/main/java/us/poliscore/PoliscoreUtil.java
sed -i '' "s|return $CURRENT_YEAR;|return $NEXT_YEAR;|g" ./webapp/src/main/webui/src/app/config.service.ts
sed -i '' "s|/$CURRENT_YEAR/|/$NEXT_YEAR/|g" ./webapp/src/main/webui/angular.json
sed -i '' "s|export YEAR=$CURRENT_YEAR|export YEAR=$NEXT_YEAR|g" ./deploy.sh
sed -i '' "s|CONGRESS=$PREVIOUS_CONGRESS|CONGRESS=$NEXT_CONGRESS|g" ./update.sh
echo "MANUAL STEP : Make sure this year is in the list of SUPPORTED_CONGRESSES in PoliScoreUtil"
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
update_property "$DB_PROPS" "quarkus.package.main-class" "us.poliscore.entrypoint.DatabaseBuilder"
update_property "$DB_PROPS" "ipGeoSecretName" "$IP_GEO_SECRET_NAME"
update_property "$DB_PROPS" "ddb.table" "$DDB_TABLE"
