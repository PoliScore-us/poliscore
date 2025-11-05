// src/app/auth/auth.interceptor.ts
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { environment } from '../../environments/environment';
import { Observable, of } from 'rxjs';
import { switchMap, take } from 'rxjs/operators';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Only attach the token to your API calls
  const isApi =
    req.url.startsWith('/api/') ||
    req.url.startsWith(environment.apiUrl); // e.g., http://localhost:8080/api

  if (!isApi) return next(req);

  const oidc = inject(OidcSecurityService);

  // Handle getAccessToken() returning either string or Observable<string>
  const tokenOr$ = (oidc as any).getAccessToken?.();
  const token$: Observable<string> =
    typeof tokenOr$ === 'string' ? of(tokenOr$) :
    tokenOr$ instanceof Observable ? tokenOr$ as Observable<string> :
    of('');

  return token$.pipe(
    take(1),
    switchMap((token) => {
      const cloned = token
        ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
        : req;
      return next(cloned);
    })
  );
};
