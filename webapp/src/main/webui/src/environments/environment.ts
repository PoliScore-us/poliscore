let baseUrl = "http://localhost:4200/";

export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080/api',
  
  cognito: {
    domain: "https://us-east-1ukl1ofrmk.auth.us-east-1.amazoncognito.com",
    clientId: '2cf7gbsb646vjei20g6cr0kio8',
    scope: 'openid profile email',
    redirectUri: baseUrl + 'auth-callback',
    postAuthRedirect: baseUrl + 'purchase/resume' // This must match an Allowed Callback URL in Cognito
  },

  stripe: {
    publicKey: 'pk_test_51SPVRzCafu3P2GuObjZCgKUIZDIwEnx8dKGHlNnlwikhxStdOoZL3iKxBYtdBFavk8x7hpQIolV6C7djkaWmWqBc005rgCEp8Z',
    productPremium: "price_1SPVyfCafu3P2GuOKKVl8wZv"
  }
};

