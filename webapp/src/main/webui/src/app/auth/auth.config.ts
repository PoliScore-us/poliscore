import { LogLevel, PassedInitialConfig } from 'angular-auth-oidc-client';
import type { OpenIdConfiguration } from 'angular-auth-oidc-client';
import { environment } from '../../environments/environment';

type ServerHints = { server?: boolean };

export function makeAuthConfig(
  redirectBase: string,
  hints: ServerHints = {}
): OpenIdConfiguration {
  return {
    authority: 'https://cognito-idp.us-east-1.amazonaws.com/us-east-1_UkL1Ofrmk',
    clientId: '2cf7gbsb646vjei20g6cr0kio8',

    // Must exactly match Cognito Allowed URLs
    redirectUrl: redirectBase + "auth-callback",
    // redirectUrl: "https://d84l1y8p4kdic.cloudfront.net",
    postLogoutRedirectUri: redirectBase,

    scope: environment.cognito.scope,
    responseType: 'code',

    // Keep server quiet: no timers/iframes on server
    silentRenew: !hints.server,
    useRefreshToken: !hints.server,

    // Auto-attach bearer to your API
    secureRoutes: ['https://api.poliscore.us', 'http://localhost:8080'],

    logLevel: hints.server ? LogLevel.None : LogLevel.Debug,

    // Prevents a redirect to '/' when we login, which allows our AuthCallbackComponent to direct us properly
    triggerAuthorizationResultEvent: true,
  };
}

