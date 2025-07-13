#!/bin/bash
# Used to set the blue/green deployment target (poliscore1 / poliscore2)

set -euo pipefail

export AWS_PAGER=""

# CloudFront distribution ID (for poliscore.us)
CLOUDFRONT_DISTRIBUTION_ID="E33KLI11KRM1QA"

# Determine the current origin from CloudFront
CURRENT_ORIGIN=$(aws cloudfront get-distribution-config --id "$CLOUDFRONT_DISTRIBUTION_ID" | jq -r '.DistributionConfig.Origins.Items[0].DomainName')

# Define deployment mappings
if [[ "$CURRENT_ORIGIN" == "poliscore-website1.s3-website-us-east-1.amazonaws.com" ]]; then
  export DDB_TABLE="poliscore2"
  export POLISCORE_DEPLOYMENT="Poliscore2"
  export IP_GEO_SECRET_NAME="Poliscore2ipgeolocation94A2-SonL4BB2tvpP"
  export LAMBDA_DEPLOYMENT_URL="https://5hta4jxn7q6cfcyxnvz4qmkyli0tambn.lambda-url.us-east-1.on.aws/"
elif [[ "$CURRENT_ORIGIN" == "poliscore-website2.s3-website-us-east-1.amazonaws.com" ]]; then
  export DDB_TABLE="poliscore1"
  export POLISCORE_DEPLOYMENT="Poliscore1"
  export IP_GEO_SECRET_NAME="Poliscore1ipgeolocation683E-N4epMkZ8yjtM"
  export LAMBDA_DEPLOYMENT_URL="https://y5i3jhm7k5vy67elvzly4b3b240kjwlp.lambda-url.us-east-1.on.aws/"
else
  echo "❌ Unrecognized origin domain: $CURRENT_ORIGIN"
  exit 1
fi

# === Set application.properties in databuilder and webapp ===
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

update_property "$WEB_PROPS" "ipGeoSecretName" "$IP_GEO_SECRET_NAME"
update_property "$WEB_PROPS" "ddb.table" "$DDB_TABLE"

# Print what was set
echo "✅ Environment variables set:"
echo "  DDB_TABLE=$DDB_TABLE"
echo "  POLISCORE_DEPLOYMENT=$POLISCORE_DEPLOYMENT"
echo "  IP_GEO_SECRET_NAME=$IP_GEO_SECRET_NAME"
echo "  LAMBDA_DEPLOYMENT_URL=$LAMBDA_DEPLOYMENT_URL"
echo "✅ Properties updated in:"
echo "  - $DB_PROPS"
echo "  - $WEB_PROPS"
