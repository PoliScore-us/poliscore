import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';
import { EntitlementService } from './entitlement.service';

// Functional-style interceptor (Angular 16+)
export const entitlementInterceptor: HttpInterceptorFn = (req, next) => {
  const state = inject(EntitlementService);
  return next(req).pipe(
    tap(event => {
      if (event instanceof HttpResponse) {
        const isAuthed = event.headers.get('X-Is-Authenticated');
        const isSub = event.headers.get('X-Is-Subscribed');

        if (isAuthed !== null) {
          state.update({ isAuthenticated: isAuthed.toLowerCase() === 'true' });
        }
        if (isSub !== null) {
          state.update({ isSubscribed: isSub.toLowerCase() === 'true' });
        }
      }
    })
  );
};
