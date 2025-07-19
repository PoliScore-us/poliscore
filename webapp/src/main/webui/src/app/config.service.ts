import { Injectable, Inject, PLATFORM_ID } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { namespace, year } from './app.config';
import sessionsData from '../assets/sessions.json';
import { Session } from './model';

@Injectable({
  providedIn: 'root'
})
export class ConfigService {
  // private congress: number;
  private currentSessionCode: string;

  private sessions: Session[] = sessionsData;

  // constructor(@Inject(PLATFORM_ID) private platformId: Object) {
  //   if (isPlatformBrowser(this.platformId)) {
  //     const baseHref = document.querySelector('base')?.getAttribute('href') || '/';
  //     const congressMatch = baseHref.match(/^\/(\d+)\/$/); // Match "/118/" or similar
  //     this.congress = congressMatch ? parseInt(congressMatch[1], 10) : 118; // Default to 118
  //   } else {
  //     this.congress = 118;
  //   }
  // }

  constructor(@Inject(PLATFORM_ID) private platformId: Object) {
    // this.congress = this.yearToCongress(this.getYear());
    this.currentSessionCode = this.lookupSession(this.getNamespace(), this.getYear())!.code;
  }

  public getYear(): number {
    // if (isPlatformBrowser(this.platformId)) {
    //   const baseHref = document.querySelector('base')?.getAttribute('href') || '/';
    //   const yearMatch = baseHref.match(/^\/(\d{4})\/$/); // Match "/2024/" or similar
    //   return yearMatch ? parseInt(yearMatch[1], 10) : new Date().getFullYear(); // Default to current year
    // } else {
    //   return new Date().getFullYear();
    // }

    return year;
  }

  public getNamespace(): string {
    return namespace;
  }

  public yearToCongressStr(year: string): string
  {
    // Congress started in 1789
    return (Math.floor((parseInt(year) - 1789) / 2) + 1).toString();
  }

  public yearToCongress(year: number): number
  {
    return Math.floor((year - 1789) / 2) + 1;
  }

  public congressToYear(congress: number): number
  {
    return (congress - 1) * 2 + 1789 + 1;
  }

  public sessionCodeToYear(sessionCode: string, namespace: string): number {
    var year = this.getYear();

    if (namespace === "us/congress")
      year = this.congressToYear(parseInt(sessionCode));
    else
      year = this.getYear(); // TODO : Converting a state session code to a year?

    return year;
  }

  public lookupSession(namespace: string, year: number): Session | undefined {
    return this.sessions.find(session =>
        session.namespace === namespace &&
        year >= session.startDate[0] &&
        year <= session.endDate[0]
    );
  }

  public getCurrentSessionCode(): string {
    return this.currentSessionCode;
  }

  public appDescription(): string
  {
    return "PoliScore uses AI to 'grade' bills and produce statistics which are aggregated up to legislators. This results in comprehensive performance metrics for congress which are rooted in policy.";
  }

  // public billIdToPath(billId: string): string
  // {
  //   return billId.replace('BIL/us/congress/' + this.congress + '/', '');
  // }

  public billIdToAbsolutePath(billId: string): string
  {
    var sessionCode = billId.split("/")[3];
    var namespace = billId.split("/")[1] + "/" + billId.split("/")[2];
    var year = String(this.sessionCodeToYear(sessionCode, namespace));

    return this.routePath(namespace, year, "bill/" +  billId.replace('BIL/' + this.getNamespace() + '/' + sessionCode + '/', ''));
  }

  public pathToBillId(path: string): string
  {
    return "BIL/" + this.getNamespace() + "/" + this.getCurrentSessionCode() + "/" + path;
  }

  // public legislatorIdToPath(legislatorId: string): string
  // {
  //   return legislatorId.replace('LEG/us/congress/' + this.congress + "/", '');
  // }

  public legislatorIdToAbsolutePath(legislatorId: string): string
  {
    var sessionCode = legislatorId.split("/")[3];
    var namespace = legislatorId.split("/")[1] + "/" + legislatorId.split("/")[2];
    var year: string = String(this.sessionCodeToYear(sessionCode, namespace));
    var bioguideId = legislatorId.split("/")[4];

    return this.routePath(namespace, year, "legislator/" + bioguideId);
  }

  public pathToLegislatorId(path: string): string
  {
    return "LEG/" + this.getNamespace() + "/" + this.getCurrentSessionCode() + "/" + path;
  }

  public routePath(namespace: string, year: string, path: string) {
    if (namespace === "us/congress") {
      return "/" + year + "/" + path;
    } else {
      return "/" + year + "/" + namespace.split("/")[1] + "/" + path;
    }
  }
}
