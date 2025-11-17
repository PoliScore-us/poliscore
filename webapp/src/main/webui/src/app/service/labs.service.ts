import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from '../app.config';

@Injectable({ providedIn: 'root' })
export class LabsService {

  constructor(private http: HttpClient) {}

  requestFeature(featureId: string): Observable<void> {
    return this.http.post<void>(backendUrl + '/labs/requestFeature', {
      featureId
    });
  }
}
