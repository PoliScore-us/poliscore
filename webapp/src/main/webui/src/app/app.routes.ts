import { Route, Routes, UrlMatchResult, UrlSegment, UrlSegmentGroup } from '@angular/router';

import { LegislatorComponent } from './legislator/legislator.component';
import { AboutComponent } from './about/about.component';
import { LegislatorsComponent } from './legislators/legislators.component';
import { BillComponent } from './bill/bill.component';
import { BillsComponent } from './bills/bills.component';
import { SessionStatsComponent } from './sessionstats/sessionstats.component';
import { PromoComponent } from './promo/promo.component';
import { AuthCallbackComponent } from './auth-callback/auth-callback.component';
import { SignupComponent } from './signup/signup.component';
import { PurchaseResumeComponent } from './billing/purchase-resume.component';
import { CheckoutSuccessComponent } from './billing/checkout-success.component';
import { CheckoutCancelComponent } from './billing/checkout-cancel.component';
import { TermsOfServiceComponent } from './legal/terms-of-service.component';
import { PrivacyPolicyComponent } from './legal/privacy-policy.component';

function idPathMatcher(path: string) {
  let p = path;
  
  return (segments: UrlSegment[], 
    group: UrlSegmentGroup, 
    route: Route) => {
      if (segments.length > 0 && segments[0].path == p) {
        return {
          consumed: segments,
          posParams: {
            id: new UrlSegment(segments.slice(1).join("/"), {})
          }
        };
      }
      
      return null;
  };
}

// function legislatorPathMatcher(path: string) {
//   let p = path;
  
//   return (segments: UrlSegment[], 
//     group: UrlSegmentGroup, 
//     route: Route) => {
//       if (segments.length > 0 && segments[0].path == p) {
//         return {
//           consumed: segments,
//           posParams: {
//             id: new UrlSegment(segments.slice(1,2).join("/"), {}),
//             index: new UrlSegment(segments.slice(2,3).join("/"), {}),
//             ascending: new UrlSegment(segments.slice(3,4).join("/"), {})
//           }
//         };
//       }
      
//       return null;
//   };
// }

export const routes: Routes = [
  { path: "", component: PromoComponent, data: { animation: 'promoPage' } },
  { path: 'auth-callback', component: AuthCallbackComponent },
  { matcher: idPathMatcher('legislator'), component: LegislatorComponent, data: { animation: 'legislatorPage' } },
  { path: 'legislators', component: LegislatorsComponent, data: { animation: 'legislatorsPage' } },
  { path: 'legislators/:index/:ascending', component: LegislatorsComponent, data: { animation: 'legislatorsPage' } },
  { path: 'bills', component: BillsComponent, data: { animation: 'billsPage' } },
  { path: 'bills/:index/:ascending', component: BillsComponent, data: { animation: 'billsPage' } },
  { matcher: idPathMatcher("bill"), component: BillComponent, data: { animation: 'billPage' } },
  { path: 'party', component: SessionStatsComponent, data: { animation: 'sessionStatsPage' } },
  { path: 'party/:party', component: SessionStatsComponent, data: { animation: 'sessionStatsPage' } },
  { path: 'party/:party/:sort', component: SessionStatsComponent, data: { animation: 'sessionStatsPage' } },

  { path: "about", redirectTo: "", pathMatch: "full" },
  { path: "signup", component: SignupComponent },

  { path: 'billing/resume', component: PurchaseResumeComponent }, // Cognito callback
  { path: 'billing/success', component: CheckoutSuccessComponent },
  { path: 'billing/cancel', component: CheckoutCancelComponent },

  { path: 'legal/terms', component: TermsOfServiceComponent },
  { path: 'legal/privacy', component: PrivacyPolicyComponent },

  
  // { path: 'about', component: PromoComponent, title: "About - PoliScore: AI Political Rating Service", data: { animation: 'about' } }
];
