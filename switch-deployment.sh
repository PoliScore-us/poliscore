#!/bin/bash

set -e

source ./set-deployment-target.sh

# Ensure POLISCORE_DEPLOYMENT is valid
if [[ "$POLISCORE_DEPLOYMENT" != "Poliscore1" && "$POLISCORE_DEPLOYMENT" != "Poliscore2" ]]; then
  echo "Error: POLISCORE_DEPLOYMENT must be 1 or 2."
  exit 1
fi

###
# Swap the cloudfront deployment, which makes the dev code now live
###

export AWS_PAGER=""

export POLISCORE_DEPLOYMENT_NUMBER="${POLISCORE_DEPLOYMENT/Poliscore/}" # extracts 1 or 2

if [ "$POLISCORE_DEPLOYMENT_NUMBER" -eq 1 ]; then
  NEW_ORIGIN="poliscore-website1.s3-website-us-east-1.amazonaws.com"
elif [ "$POLISCORE_DEPLOYMENT_NUMBER" -eq 2 ]; then
  NEW_ORIGIN="poliscore-website2.s3-website-us-east-1.amazonaws.com"
else
  echo "Invalid POLISCORE_DEPLOYMENT_NUMBER: Please specify 1 or 2."
  exit 1
fi

# CloudFront distribution IDs
DISTRIBUTION_1="E33KLI11KRM1QA"
DISTRIBUTION_2="E2MZLE77EVKTB"
DISTRIBUTION_TEST="E3FIR5Y0R5OC5I"

# Function to update CloudFront distribution
update_distribution() {
  local DISTRIBUTION_ID=$1
  local DOMAIN_NAME=$2
  local CONFIG_JSON="config_$DISTRIBUTION_ID.json"

  # Get the current distribution config
  aws cloudfront get-distribution-config --id "$DISTRIBUTION_ID" > "$CONFIG_JSON"

  # Extract the ETag required for updates
  ETAG=$(jq -r '.ETag' "$CONFIG_JSON")

  # Modify the origin domain name
  jq --arg NEW_ORIGIN "$NEW_ORIGIN" '.DistributionConfig.Origins.Items[0].DomainName = $NEW_ORIGIN | .DistributionConfig' "$CONFIG_JSON" > "final_$CONFIG_JSON"

  # Update the CloudFront distribution
  aws cloudfront update-distribution --id "$DISTRIBUTION_ID" --if-match "$ETAG" --distribution-config file://final_$CONFIG_JSON

  # Cleanup temporary files
  rm "$CONFIG_JSON" "final_${CONFIG_JSON}"

  echo "Updated CloudFront distribution ($DISTRIBUTION_ID) to use origin: $NEW_ORIGIN"
}

# Function to update test distribution to the origin NOT currently in production
update_distribution_invert() {
  local DISTRIBUTION_ID=$1
  local CONFIG_JSON="config_$DISTRIBUTION_ID.json"

  # Determine the inverse origin
  if [ "$POLISCORE_DEPLOYMENT_NUMBER" -eq 1 ]; then
    INVERT_ORIGIN="poliscore-website2.s3-website-us-east-1.amazonaws.com"
  else
    INVERT_ORIGIN="poliscore-website1.s3-website-us-east-1.amazonaws.com"
  fi

  # Get current config
  aws cloudfront get-distribution-config --id "$DISTRIBUTION_ID" > "$CONFIG_JSON"

  # Extract ETag
  ETAG=$(jq -r '.ETag' "$CONFIG_JSON")

  # Modify origin
  jq --arg NEW_ORIGIN "$INVERT_ORIGIN" '.DistributionConfig.Origins.Items[0].DomainName = $NEW_ORIGIN | .DistributionConfig' "$CONFIG_JSON" > "final_$CONFIG_JSON"

  # Update distribution
  aws cloudfront update-distribution --id "$DISTRIBUTION_ID" --if-match "$ETAG" --distribution-config file://final_$CONFIG_JSON

  # Cleanup
  rm "$CONFIG_JSON" "final_$CONFIG_JSON"

  echo "Updated TEST CloudFront distribution ($DISTRIBUTION_ID) to use origin: $INVERT_ORIGIN"
}


# Update both distributions
update_distribution "$DISTRIBUTION_1" "poliscore.us"
update_distribution "$DISTRIBUTION_2" "www.poliscore.us"
update_distribution_invert "$DISTRIBUTION_TEST"

./set-deployment-target.sh

echo "CloudFront distributions updated successfully."
echo "You may continue development on the test distribution at - d1q121pqch444h.cloudfront.net"

