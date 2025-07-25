import { Route, Routes, UrlMatchResult, UrlSegment, UrlSegmentGroup } from '@angular/router';

import { LegislatorComponent } from './legislator/legislator.component';
import { AboutComponent } from './about/about.component';
import { LegislatorsComponent } from './legislators/legislators.component';
import { BillComponent } from './bill/bill.component';
import { BillsComponent } from './bills/bills.component';
import { SessionStatsComponent } from './sessionstats/sessionstats.component';
import { PromoComponent } from './promo/promo.component';

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
  { matcher: idPathMatcher('legislator'), component: LegislatorComponent, data: { animation: 'legislatorPage' } },
  { path: 'legislators', component: LegislatorsComponent, data: { animation: 'legislatorsPage' } },
  { path: 'legislators/:index/:ascending', component: LegislatorsComponent, data: { animation: 'legislatorsPage' } },
  { path: 'bills', component: BillsComponent, data: { animation: 'billsPage' } },
  { path: 'bills/:index/:ascending', component: BillsComponent, data: { animation: 'billsPage' } },
  { matcher: idPathMatcher("bill"), component: BillComponent, data: { animation: 'billPage' } },
  { path: 'party', component: SessionStatsComponent, data: { animation: 'sessionStatsPage' } },
  { path: 'party/:party', component: SessionStatsComponent, data: { animation: 'sessionStatsPage' } },
  { path: 'party/:party/:sort', component: SessionStatsComponent, data: { animation: 'sessionStatsPage' } },

  { path: "about", redirectTo: "", pathMatch: "full" }
  // { path: 'about', component: PromoComponent, title: "About - PoliScore: AI Political Rating Service", data: { animation: 'about' } }
];
