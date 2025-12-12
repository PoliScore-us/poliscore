// let baseUrl = "https://d1q121pqch444h.cloudfront.net/";
let baseUrl = "https://poliscore.us/"

export const environment = {
  production: true,
  baseUrl: baseUrl,
  apiUrl: 'https://5hta4jxn7q6cfcyxnvz4qmkyli0tambn.lambda-url.us-east-1.on.aws/',
  
  cognito: {
    domain: "https://us-east-1ukl1ofrmk.auth.us-east-1.amazoncognito.com",
    clientId: '2cf7gbsb646vjei20g6cr0kio8',
    scope: 'openid profile email',
    redirectUri: baseUrl + 'auth-callback',
    postAuthRedirect: baseUrl + 'purchase/resume' // This must match an Allowed Callback URL in Cognito
  },

  stripe: {
    // prod
    publicKey: 'pk_live_51PfR5YE3jV2gS3sQhMAmdY1j4B1INHpdA1m5jR3ZoQPAUGxNbTqDVgYjhWQQlWeC6WB22yo6NGJG9IBEElysdW5i00XreDbaPi',
    productPremium: "price_1SSQLvE3jV2gS3sQjvzSHJQl"

    // dev
    // publicKey: 'pk_test_51SPVRzCafu3P2GuObjZCgKUIZDIwEnx8dKGHlNnlwikhxStdOoZL3iKxBYtdBFavk8x7hpQIolV6C7djkaWmWqBc005rgCEp8Z',
    // productPremium: "price_1SPVyfCafu3P2GuOKKVl8wZv"
  }
};

