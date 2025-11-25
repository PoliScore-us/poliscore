import { Component, HostListener, Inject, inject, Input, OnInit, PLATFORM_ID } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterModule } from '@angular/router';
import { ConfigService } from '../config.service';
import {MatSelectModule} from '@angular/material/select';
import convertStateCodeToName from '../model';
import { AuthService } from '../auth/auth.service';
import { BillingService } from '../billing/billing.service';
import { MatMenuModule } from '@angular/material/menu';
import { MatIconModule } from '@angular/material/icon';
import { EntitlementService } from '../billing/entitlement.service';
import { Subscription } from 'rxjs';
import { PurchaseFlowService } from '../billing/purchase-flow.service';
import { environment } from '../../environments/environment';
import { TrackClickDirective } from '../track-click.directive';

@Component({
  selector: 'header',
  standalone: true,
  imports: [CommonModule, MatButtonModule, RouterModule, MatSelectModule, MatMenuModule, MatIconModule, TrackClickDirective],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss'
})
export class HeaderComponent implements OnInit {

  @Input() public legislators: boolean = true;
  @Input() public bills: boolean = true;
  @Input() public congress: boolean = true;
  @Input() public about: boolean = true;

  auth = inject(AuthService);
  billingService = inject(BillingService);
  entitlement = inject(EntitlementService);
  private flow = inject(PurchaseFlowService);

  public year: number = 2024;
  public years = [2026];

  public namespace: String = "us/congress";
  public namespaces = ["us/congress", "us/co"];

  private sub?: Subscription;

  subscriptionLoaded: boolean = false;
  
  isSubscribed: boolean = false;

  isSmallScreen: boolean = false;

  public routingNamespace = "/";

  public assetPrefix = "";

  constructor(public config: ConfigService, private router: Router, @Inject(PLATFORM_ID) private _platformId: Object) { 
    this.year = config.getYear();
    this.namespace = config.getNamespace();
    this.assetPrefix = config.getAssetRoutingPrefix();

    if (this.namespace === "us/congress")
      this.routingNamespace = "/";
    else
      this.routingNamespace = "/" + this.namespace.substring(3);

    if (this.namespace === 'us/congress') {
      this.years = [2026, 2024];
    } else {
      this.years = [2025];
    }

    // this.removeLatestYear();
  }

  ngOnInit(): void {
    this.subscriptionLoaded = false;

    this.sub = this.entitlement.status$.subscribe(s => {
      this.subscriptionLoaded = true;
      this.isSubscribed = s.isSubscribed;
     });

    if (isPlatformBrowser(this._platformId))
      this.isSmallScreen = window.innerWidth < 600;
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  @HostListener('window:resize', ['$event'])
  onResize() {
    // Check screen width on resize
    this.isSmallScreen = window.innerWidth < 600;
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

  public getTagLine(): string {
    return this.config.getTagline();
  }

  public onChangeYear(year: number) {
      const currentUrl = new URL(window.location.href);
      const pathSegments = currentUrl.pathname.split('/').filter(seg => seg); // Remove empty segments

      // Extract the second segment (i.e., the main category after the year)
      const mainCategory = pathSegments[1] || '';

      // List of categories that should reset to the root year URL
      const resetCategories = ['bill', 'legislator'];

      // Determine new URL
      // TODO : Hardcoded to current setup
      let newUrl: string = "";
      if (year != 2025 && year != 2026)
        newUrl = `/${year}`;

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

    // TODO : And what about other years??
    if (ns === 'us/congress') {
      newUrl = "/legislators";
    } else {
      newUrl = ns.split("/")[1] + "/legislators";
    }

    // Navigate to the new URL
    window.location.href = newUrl;
  }

  async onManageSubscription() {
    await this.billingService.getCustomerPortalUrl();
  }

  signUp() {
    this.flow.start(environment.stripe.productPremium);
  }

}
