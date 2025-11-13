import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

export interface EntitlementStatus {
  isAuthenticated: boolean;
  isSubscribed: boolean;
}

@Injectable({ providedIn: 'root' })
export class EntitlementService {
  // internal state
  private _status: EntitlementStatus | null = null;

  // no constructor args — SSR safe
  private readonly _status$ = new Subject<EntitlementStatus>();
  readonly status$ = this._status$.asObservable();

  get snapshot(): EntitlementStatus | null {
    return this._status;
  }

  update(partial: Partial<EntitlementStatus>) {
    this._status = { ...(this._status ?? {} as EntitlementStatus), ...partial };
    this._status$.next(this._status);
  }
}
