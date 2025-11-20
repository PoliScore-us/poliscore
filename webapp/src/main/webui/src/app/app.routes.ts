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

function billPathMatcher(
  segments: UrlSegment[],
  group: UrlSegmentGroup,
  route: Route
): UrlMatchResult | null {
  if (!segments.length) return null;

  const { index, year, state } = parseYearState(segments);
  let i = index;

  // Expect "bill"
  if (!segments[i] || segments[i].path !== 'bill') return null;

  // Everything after "bill" is the bill id (e.g. "s/123")
  const idSegments = segments.slice(i + 1);
  if (!idSegments.length) return null;

  const posParams: { [key: string]: UrlSegment } = {
    id: new UrlSegment(idSegments.map(s => s.path).join('/'), {})
  };

  if (year) posParams['year'] = year;
  if (state) posParams['state'] = state;

  return { consumed: segments, posParams };
}

function legislatorPathMatcher(
  segments: UrlSegment[],
  group: UrlSegmentGroup,
  route: Route
): UrlMatchResult | null {
  if (!segments.length) return null;

  const { index, year, state } = parseYearState(segments);
  let i = index;

  // Expect "legislator"
  if (!segments[i] || segments[i].path !== 'legislator') return null;

  // Everything after "legislator" is the legislator id
  const idSegments = segments.slice(i + 1);
  if (!idSegments.length) return null;

  const posParams: { [key: string]: UrlSegment } = {
    id: new UrlSegment(idSegments.map(s => s.path).join('/'), {})
  };

  if (year) posParams['year'] = year;
  if (state) posParams['state'] = state;

  return { consumed: segments, posParams };
}

function legislatorsPathMatcher(
  segments: UrlSegment[],
  group: UrlSegmentGroup,
  route: Route
): UrlMatchResult | null {
  if (!segments.length) return null;

  const { index, year, state } = parseYearState(segments);
  let i = index;

  // Expect "legislators"
  if (!segments[i] || segments[i].path !== 'legislators') return null;

  const rest = segments.slice(i + 1); // [index?, ascending?]

  const posParams: { [key: string]: UrlSegment } = {};

  if (year) posParams['year'] = year;
  if (state) posParams['state'] = state;
  if (rest[0]) posParams['index'] = rest[0];
  if (rest[1]) posParams['ascending'] = rest[1];

  return { consumed: segments, posParams };
}

function billsPathMatcher(
  segments: UrlSegment[],
  group: UrlSegmentGroup,
  route: Route
): UrlMatchResult | null {
  if (!segments.length) return null;

  const { index, year, state } = parseYearState(segments);
  let i = index;

  // Expect "bills"
  if (!segments[i] || segments[i].path !== 'bills') return null;

  const rest = segments.slice(i + 1); // [index?, ascending?]

  const posParams: { [key: string]: UrlSegment } = {};

  if (year) posParams['year'] = year;
  if (state) posParams['state'] = state;
  if (rest[0]) posParams['index'] = rest[0];
  if (rest[1]) posParams['ascending'] = rest[1];

  return { consumed: segments, posParams };
}

function partyPathMatcher(
  segments: UrlSegment[],
  group: UrlSegmentGroup,
  route: Route
): UrlMatchResult | null {
  if (!segments.length) return null;

  const { index, year, state } = parseYearState(segments);
  let i = index;

  // Expect "party"
  if (!segments[i] || segments[i].path !== 'party') return null;

  const rest = segments.slice(i + 1); // [party?, sort?]

  const posParams: { [key: string]: UrlSegment } = {};

  if (year) posParams['year'] = year;
  if (state) posParams['state'] = state;
  if (rest[0]) posParams['party'] = rest[0];
  if (rest[1]) posParams['sort'] = rest[1];

  return { consumed: segments, posParams };
}

function parseYearState(
  segments: UrlSegment[],
  startIndex = 0
): { index: number; year?: UrlSegment; state?: UrlSegment } {
  let index = startIndex;
  let year: UrlSegment | undefined;
  let state: UrlSegment | undefined;

  // Optional year: 4 digits
  if (segments[index] && /^\d{4}$/.test(segments[index].path)) {
    year = segments[index];
    index++;
  }

  // Optional state: 2 letters
  if (segments[index] && /^[a-z]{2}$/i.test(segments[index].path)) {
    state = segments[index];
    index++;
  }

  return { index, year, state };
}

export const routes: Routes = [
  { path: "", component: PromoComponent, data: { animation: 'promoPage' } },
  { path: 'auth-callback', component: AuthCallbackComponent },

  // Single legislator with optional year/state
  { matcher: legislatorPathMatcher, component: LegislatorComponent, data: { animation: 'legislatorPage' } },

  // Legislators list with optional year/state and optional index/ascending
  { matcher: legislatorsPathMatcher, component: LegislatorsComponent, data: { animation: 'legislatorsPage' } },

  // Bills list with optional year/state and optional index/ascending
  { matcher: billsPathMatcher, component: BillsComponent, data: { animation: 'billsPage' } },

  // Single bill with optional year/state
  { matcher: billPathMatcher, component: BillComponent, data: { animation: 'billPage' } },

  // Party stats with optional year/state and optional party/sort
  { matcher: partyPathMatcher, component: SessionStatsComponent, data: { animation: 'sessionStatsPage' } },

  { path: "about", redirectTo: "", pathMatch: "full" },
  { path: "signup", component: SignupComponent },

  { path: 'billing/resume', component: PurchaseResumeComponent },
  { path: 'billing/success', component: CheckoutSuccessComponent },
  { path: 'billing/cancel', component: CheckoutCancelComponent },

  { path: 'legal/terms', component: TermsOfServiceComponent },
  { path: 'legal/privacy', component: PrivacyPolicyComponent },
];
