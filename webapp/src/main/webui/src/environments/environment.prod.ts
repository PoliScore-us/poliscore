let baseUrl = "https://poliscore.us/";

export const environment = {
  production: true,
  apiUrl: 'TODO',
  
  cognito: {
    domain: "https://us-east-1ukl1ofrmk.auth.us-east-1.amazoncognito.com",
    clientId: '2cf7gbsb646vjei20g6cr0kio8',
    scope: 'openid profile email',
    redirectUri: baseUrl + 'auth-callback',
    postAuthRedirect: baseUrl + 'purchase/resume' // This must match an Allowed Callback URL in Cognito
  },

  stripe: {
    publicKey: 'pk_live_51PfR5YE3jV2gS3sQhMAmdY1j4B1INHpdA1m5jR3ZoQPAUGxNbTqDVgYjhWQQlWeC6WB22yo6NGJG9IBEElysdW5i00XreDbaPi',
    productPremium: "prod_TMERVtegl3x9zM"
  }
};

