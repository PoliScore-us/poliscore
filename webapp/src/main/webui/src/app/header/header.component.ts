import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterModule } from '@angular/router';
import { ConfigService } from '../config.service';
import {MatSelectModule} from '@angular/material/select';
import convertStateCodeToName from '../model';

@Component({
  selector: 'header',
  standalone: true,
  imports: [CommonModule, MatButtonModule, RouterModule, MatSelectModule],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss'
})
export class HeaderComponent {

  @Input() public legislators: boolean = true;
  @Input() public bills: boolean = true;
  @Input() public congress: boolean = true;
  @Input() public about: boolean = true;

  public year: number = 2024;
  public years = [2026];

  public namespace: String = "us/congress";
  public namespaces = ["us/congress", "us/co"];

  constructor(public config: ConfigService, private router: Router) { 
    this.year = config.getYear();
    this.namespace = config.getNamespace();

    if (this.namespace === 'us/congress') {
      this.years = [2026, 2024];
    } else {
      this.years = [2025];
    }

    // this.removeLatestYear();
  }

  private removeLatestYear(): void {
    const urlSegments = this.router.url.split('/');
    
    if (urlSegments.length > 1 && urlSegments[1] === 'congress') {
      this.years.shift();
      this.year = this.years[0];
    }
  }

  namespaceDisplayLabel(ns: string) {
    if (ns === "us/congress") {
      return "Congress";
    } else {
      return convertStateCodeToName(ns.split("/")[1]);
    }
  }

  public yearDisplayLabel(year: number) {
    if (this.namespace === 'us/congress')
      return year + " (" + this.config.yearToCongress(year) + "th)";
    else
      return year;
  }

  public onChangeYear(year: number) {
      const currentUrl = new URL(window.location.href);
      const pathSegments = currentUrl.pathname.split('/').filter(seg => seg); // Remove empty segments

      // Extract the second segment (i.e., the main category after the year)
      const mainCategory = pathSegments[1] || '';

      // List of categories that should reset to the root year URL
      const resetCategories = ['bill', 'legislator'];

      // Determine new URL
      let newUrl = `/${year}`;

      if (!resetCategories.includes(mainCategory)) {
          newUrl += '/' + pathSegments.slice(1).join('/'); // Preserve pathing if relevant
      }

      // Append hash parameters if present
      if (currentUrl.hash && !(year === 2024 && (currentUrl.hash.includes('hot') || currentUrl.hash.includes("byimpactabs")))) {
          newUrl += currentUrl.hash;
      }

      // Navigate to the new URL
      window.location.href = newUrl;
  }

  onChangeNamespace(ns: string) {
    let newUrl = "";

    // TODO : Way too hardcoded over here but a year for one namespace might not be relevant for a year for a different namespace
    if (ns === 'us/congress') {
      newUrl = "/2026";
    } else {
      newUrl = "/2025/" + ns.split("/")[1];
    }

    // Navigate to the new URL
    window.location.href = newUrl;
  }

}
