import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export interface EntitlementStatus {
  isAuthenticated: boolean;
  isSubscribed: boolean;
}

@Injectable({ providedIn: 'root' })
export class EntitlementService {
  private readonly _status$ = new BehaviorSubject<EntitlementStatus>({
    isAuthenticated: false,
    isSubscribed: false,
  });

  readonly status$ = this._status$.asObservable();

  get snapshot(): EntitlementStatus {
    return this._status$.value;
  }

  update(partial: Partial<EntitlementStatus>) {
    this._status$.next({ ...this._status$.value, ...partial });
  }
}
