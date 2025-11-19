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

  public getTagline(): string {
    return "AI Impact Analysis Service";
  }

  public sessionCodeToYear(sessionCode: string, namespace: string): number {
    var year = this.getYear();

    if (namespace === "us/congress")
      year = this.congressToYear(parseInt(sessionCode));
    else
      year = this.sessions.find(session =>
            session.namespace === namespace &&
            sessionCode === session.code
        )!.endDate[0];

    return year;
  }

  public lookupSession(namespace: string, year: number, regular: boolean = true): Session | undefined {
    return this.sessions.find(session =>
        session.regular === regular &&
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

    if (namespace === 'us/congress')
      return this.routePath(namespace, year, "bill/" +  billId.split("/").slice(4).join("/"));
    else
      return this.routePath(namespace, year, "bill/" +  billId.replace('BIL/' + this.getNamespace() + '/', ''));
  }

  public pathToBillId(path: string): string
  {
    if (this.getNamespace() === 'us/congress')
      return "BIL/" + this.getNamespace() + "/" + this.getCurrentSessionCode() + "/" + path;
    else
      return "BIL/" + this.getNamespace() + "/" + path;
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

  /**
   * Relative pathing is returned for paths inside our same angular deployment. Absolute paths are returned for
   * links in a different angular deployment. This allows us to utilize a baseHref of '/' for local dev and a
   * separate deployed baseHref (which is essential for OIDC )
   * 
   * @param namespace 
   * @param year 
   * @param path 
   * @returns 
   */
  public routePath(namespace: string, year: string, path: string) {
    let sameApp = namespace === this.getNamespace() && year === String(this.getYear());
    if (sameApp) return path;

    if (namespace === "us/congress") {
      if (path.startsWith("bill/" + this.currentSessionCode))
        path = path.replace("bill/" + this.currentSessionCode, "bill");

      if (path.startsWith("legislator"))
        return "/" + path;
      else
        return "/" + year + "/" + path;
    } else {
      return "/" + year + "/" + namespace.split("/")[1] + "/" + path;
    }
  }
}
