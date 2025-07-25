import { AfterViewInit, Component, ElementRef, HostListener, Inject, OnInit, PLATFORM_ID, Renderer2, ViewChild } from '@angular/core';
import { AppService } from '../app.service';
import convertStateCodeToName, { Legislator, issueKeyToLabel, getBenefitToSocietyIssue, IssueStats, gradeForStats, BillInteraction, colorForGrade, issueKeyToLabelSmall, Page, hasValidInterpretation, issueMap, PageIndex } from '../model';
import { HttpHeaders, HttpClient, HttpParams, HttpHandler } from '@angular/common/http';
import { CommonModule, DatePipe, KeyValuePipe, isPlatformBrowser } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { Chart, ChartConfiguration, BarController, CategoryScale, LinearScale, BarElement, Tooltip} from 'chart.js'
import ChartDataLabels from 'chartjs-plugin-datalabels';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card'; 
import { MatTableModule } from '@angular/material/table';
import { Meta, Title } from '@angular/platform-browser';
import { MatButtonModule } from '@angular/material/button';
import { ConfigService } from '../config.service';
import { HeaderComponent } from '../header/header.component';
import { DisclaimerComponent } from '../disclaimer/disclaimer.component';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu'; 

/*
const floatingLabelsPlugin = {
  id: 'floating-labels',
  afterDatasetsDraw: function (chart: Chart) {

    const ctx = chart.ctx;
    ctx.fillStyle = 'rgb(255, 255, 255)';
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'bottom';
    ctx.fillText("Test", chart.chartArea.width / 2, chart.chartArea.height / 2);
  }
};
*/

Chart.register(BarController, CategoryScale, LinearScale, BarElement, ChartDataLabels, Tooltip);

export const CHART_COLORS = {
  red: 'rgb(255, 99, 132)',
  orange: 'rgb(255, 159, 64)',
  yellow: 'rgb(255, 205, 86)',
  green: 'rgb(75, 192, 192)',
  blue: 'rgb(54, 162, 235)',
  purple: 'rgb(153, 102, 255)',
  grey: 'rgb(201, 203, 207)'
};

@Component({
  selector: 'app-legislator',
  standalone: true,
  imports: [MatMenuModule, MatButtonToggleModule, DisclaimerComponent, HeaderComponent, KeyValuePipe, CommonModule, BaseChartDirective, MatCardModule, MatTableModule, DatePipe, RouterModule, MatButtonModule],
  providers: [AppService, HttpClient],
  templateUrl: './legislator.component.html',
  styleUrl: './legislator.component.scss'
})
export class LegislatorComponent implements OnInit, AfterViewInit {
  @ViewChild("barChart") barChart!: HTMLCanvasElement;

  @ViewChild("interactTable") interactTable!: ElementRef;

  displayedColumns: string[] = ['billName', 'billGrade', "association", "date"];
  public billData?: any;

  public leg?: Legislator;

  private legId?: string;

  public loading: boolean = true;

  public isRequestingData: boolean = false;

  public hasValidInterp: boolean = false;

  private hasMoreData: boolean = true;

  private chart: any = null;

  issueMap = issueMap;

  selectedOption: string = '';

  public barChartData: ChartConfiguration<'bar'>['data'] = {
    labels: [],
    datasets: []
  };

  public barChartOptions: ChartConfiguration<'bar'>['options'] = {
    indexAxis: "y",
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
      legend: {
        position: 'top',
      },
      title: {
        display: true,
        text: 'Chart.js Floating Bar Chart'
      },
      datalabels: {
        anchor: (ctx) => {
          return ctx.dataset.data[ctx.dataIndex] as number >= 0 ? "start" : "end";
        },
        align: 'center', // Align the text after the anchor point
        formatter: function (value, context) { // Show the label instead of the value
          return context?.chart?.data?.labels![context.dataIndex];
        },
        // font: { weight: "bold" }
      }
    },
    onClick: (event, elements) => {
      if (elements.length > 0) {
        const element = elements[0];
        const datasetIndex = element.datasetIndex;
        const dataIndex = element.index;

        var issue = Object.entries(this.issueMap).filter(i => i[1].toLowerCase() === this.chart.data.labels[dataIndex].toLowerCase())[0][0];

        if (this.page.sortKey !== issue) {
          // If the value is negative, then we want to sort by ascending to show their worst bills first
          // If we set an existing value to descending then when we toggle it will be ascending
          if (this.chart.data.datasets[datasetIndex].data[dataIndex] < 0)
          {
            this.page.index = "TrackedIssue";
            this.page.sortKey = issue;
            this.page.ascending = false;
          }

          this.togglePage("TrackedIssue", issue);

          document.querySelector('.interactions-table')!.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      } else {
        console.log('No element clicked');
      }
    },
    scales: {
      x: {
        min: -100,
        max: 100
      },
      // y: {ticks: {mirror: true, crossAlign: "center", align: "center", z: 1}}
      y: { ticks: { display: false } }
    }
  };

  public page: Page = {
    index: "ObjectsByRatingAbs",
    ascending: undefined,
    pageSize: 25
  };

  constructor(public config: ConfigService, private meta: Meta, private service: AppService, private route: ActivatedRoute, private router: Router, @Inject(PLATFORM_ID) private _platformId: Object, private titleService: Title) { }

  ngOnInit(): void {
    this.legId = this.route.snapshot.paramMap.get('id') as string;
    if (!this.legId.startsWith("LEG/" + this.config.getNamespace())) {
      this.legId = this.config.pathToLegislatorId(this.legId);
    }
  
    // Parse hash fragment for parameters
    const fragment = this.route.snapshot.fragment;
    const params = new URLSearchParams(fragment || '');
  
    const routeIndex = params.get('sort');
    const routeAscending = params.get('ascending') == null ? undefined : (params.get('ascending') === 'true');
  
    if (routeIndex === "bydate") {
      this.page.index = "ObjectsByDate";
    } else if (routeIndex === "bygrade" || routeIndex === 'byrating') {
      this.page.index = "ObjectsByRating";
    } else if (routeIndex === "bygradeabs" || routeIndex === 'byratingabs') {
      this.page.index = "ObjectsByRatingAbs";
    } else if (routeIndex === "byimpact") {
      this.page.index = "ObjectsByImpact";
    } else if (routeIndex === "byimpactabs") {
      this.page.index = "ObjectsByImpactAbs";
    } else if (routeIndex && routeIndex.length > 0) {
      this.page.index = "TrackedIssue";
      this.page.sortKey = routeIndex!;
    }

    this.page.ascending = routeAscending;
  
    this.service.getLegislator(this.legId, this.page).then((leg) => {
      this.leg = leg;
      
      if (leg == null) return;
      this.loading = false;
      this.hasValidInterp = hasValidInterpretation(this.leg);

      // if (leg.interpretation != null && leg.interpretation.issueStats.stats['OverallBenefitToSociety'] < 0 && params.get('ascending') == null)
      //   this.page.index = "ObjectsByImpact";
      //   this.page.ascending = true;
  
      this.updateMetaTags();
  
      this.refreshBillData();
  
      this.buildBarChartData();
    });
  }

  getPreviousYear(): number {
    const currentYear: number = new Date().getFullYear();
    return currentYear - 1;
  }
  
  isNonSafari() {
    return !(navigator.userAgent.includes('Safari') && !navigator.userAgent.includes('Chrome'));
  }

  ngAfterViewInit() {
    if (isPlatformBrowser(this._platformId)) {
      this.buildBarChartData();
    }
  }

  updateMetaTags(): void {
    if (!this.leg) return;

    let session = parseInt(this.legId!.split("/")[3]);
    let year = this.config.congressToYear(session);

    const pageTitle = this.leg!.name.official_full + " - PoliScore: AI Political Rating Service";

    let pageDescription = "Waiting for more data - Legislator interpretatons require at least a hundred bill interactions in order to ensure accuracy.";
    if (this.leg!.interpretation && this.leg!.interpretation!.longExplain)
      pageDescription = this.gradeForLegislator() + " - " + this.leg!.interpretation!.longExplain!.split(/\n+/)[0].trim();

    const pageUrl = `https://poliscore.us` + this.config.legislatorIdToAbsolutePath(this.legId!);
    const imageUrl = this.photoForLegislator();

    this.titleService.setTitle(pageTitle);
    
    this.meta.updateTag({ property: 'og:title', content: pageTitle });
    this.meta.updateTag({ property: 'og:description', content: pageDescription });
    this.meta.updateTag({ property: 'og:url', content: pageUrl });
    this.meta.updateTag({ property: 'og:image', content: imageUrl });
    this.meta.updateTag({ property: 'og:type', content: 'website' });

    // Twitter meta tags (optional)
    this.meta.updateTag({ name: 'twitter:card', content: 'summary_large_image' });
    this.meta.updateTag({ name: 'twitter:title', content: pageTitle });
    this.meta.updateTag({ name: 'twitter:description', content: pageDescription });
    this.meta.updateTag({ name: 'twitter:image', content: imageUrl });
  }

  upForReelection() {
    return this.leg && this.leg!.terms![this.leg!.terms!.length - 1].endDate === (new Date().getFullYear() + 1) + '-01-03';
  }

  refreshBillData() {
    this.billData = this.leg?.interactions
        ?.filter(i => i.issueStats != null)
        .map(i => ({
          billName: i.billName,
          billGrade: gradeForStats(i.issueStats),
          date: new Date(parseInt(i.date.split("-")[0]), parseInt(i.date.split("-")[1]) - 1, parseInt(i.date.split("-")[2])),
          association: this.describeAssociation(i),
          billId: i.billId,
          statusProgress: i.statusProgress
        }));
        // .sort((a, b) => b.date.getTime() - a.date.getTime());
  }

  photoForLegislator(): string {
    return 'https://poliscore-prod-public.s3.amazonaws.com/' + this.leg?.id + '.webp';
  }

  gradeForLegislator(): string {
    if (!hasValidInterpretation(this.leg))
      return "";

    return gradeForStats(this.leg?.interpretation?.issueStats!);
  }

  colorForGrade(grade: string): string { return colorForGrade(this.gradeForLegislator()); }

  officialUrlForLegislator() {
    if (this.leg == null) return null;

    let namespace = this.leg!.id?.split("/")[1] + "/" + this.leg!.id?.split("/")[2];
    let sessionCode = this.leg!.id?.split("/")[3];
    let lastTerm = this.leg!.terms[this.leg!.terms.length-1];
    let code = this.leg.id?.split("/").pop();

    if (namespace === "us/congress") {
      return 'https://www.congress.gov/member/' + this.leg?.name?.first?.toLowerCase()?.replace(' ', '-') + '-' + this.leg?.name?.last?.toLowerCase()?.replace(' ', '-') + '/' + code;
    } else if (namespace === "us/al") {
      return 'https://www.legislature.state.al.us/aliswww/ISD/ALRepresentative.aspx?NAME=' + this.leg?.name?.last;
    } else if (namespace === "us/ak") {
      return 'http://akleg.gov/legislator.php?id=' + this.leg!.id;
    } else if (namespace === "us/az") {
      return 'https://www.azleg.gov/MemberRoster/?body=' + (lastTerm.chamber === 'UPPER' ? 'S' : 'H');
    } else if (namespace === "us/ar") {
      return 'https://www.arkleg.state.ar.us/Legislators/Detail?member=' + this.leg?.name?.last;
    } else if (namespace === "us/ca") {
      return 'https://findyourrep.legislature.ca.gov/';
    } else if (namespace === "us/co") {
      return 'https://leg.colorado.gov/legislators/' + this.leg?.name?.first?.toLowerCase() + '-' + this.leg?.name?.last?.toLowerCase();
    } else if (namespace === "us/ct") {
      return 'https://www.cga.ct.gov/asp/menu/cgafindleg.asp';
    } else if (namespace === "us/de") {
      return 'https://legis.delaware.gov/Legislator-Detail?personId=' + this.leg!.id;
    } else if (namespace === "us/fl") {
      if (lastTerm.chamber === 'UPPER') {
        return 'https://www.flsenate.gov/Senators/' + lastTerm.district;
      } else {
        return 'https://www.myfloridahouse.gov/Sections/Representatives/representatives.aspx';
      }
    } else if (namespace === "us/ga") {
      return 'https://www.legis.ga.gov/members/' + (lastTerm.chamber === 'UPPER' ? 'senate' : 'house') + '/' + this.leg!.id;
    } else if (namespace === "us/hi") {
      return 'https://www.capitol.hawaii.gov/legislator.aspx?member=' + this.leg?.name?.last;
    } else if (namespace === "us/id") {
      return 'https://legislature.idaho.gov/legislators/membership/';
    } else if (namespace === "us/il") {
      return 'https://www.ilga.gov/house/Rep.asp?MemberID=' + this.leg!.id;
    } else if (namespace === "us/in") {
      return 'https://iga.in.gov/legislative/2024/legislators/' + this.leg!.id;
    } else if (namespace === "us/ia") {
      return 'https://www.legis.iowa.gov/legislators/legislator?ga=' + sessionCode + '&personID=' + this.leg!.id;
    } else if (namespace === "us/ks") {
      return 'http://www.kslegislature.org/li/b2023_24/members/' + this.leg!.id;
    } else if (namespace === "us/ky") {
      return 'https://legislature.ky.gov/Legislators/' + (lastTerm.chamber === 'UPPER' ? 'senate' : 'house') + '/Pages/default.aspx';
    } else if (namespace === "us/la") {
      return 'https://www.legis.la.gov/legis/FindMyLegislators.aspx';
    } else if (namespace === "us/me") {
      return 'https://legislature.maine.gov/house/house/MemberProfiles/ListAlphaTown';
    } else if (namespace === "us/md") {
      return 'https://mgaleg.maryland.gov/mgawebsite/Members/Index/';
    } else if (namespace === "us/ma") {
      return 'https://malegislature.gov/Legislators/Members/';
    } else if (namespace === "us/mi") {
      return 'https://www.house.mi.gov/all-representatives';
    } else if (namespace === "us/mn") {
      return 'https://www.leg.mn.gov/legdb/';
    } else if (namespace === "us/ms") {
      return 'https://billstatus.ls.state.ms.us/members/';
    } else if (namespace === "us/mo") {
      return 'https://house.mo.gov/MemberDetails.aspx?year=' + sessionCode + '&code=' + this.leg!.id;
    } else if (namespace === "us/mt") {
      return 'https://leg.mt.gov/legislator-information/';
    } else if (namespace === "us/ne") {
      return 'https://nebraskalegislature.gov/senators/senator_find.php';
    } else if (namespace === "us/nv") {
      return 'https://www.leg.state.nv.us/App/Legislator/A/Assembly/';
    } else if (namespace === "us/nh") {
      return 'https://www.gencourt.state.nh.us/house/members/default.aspx';
    } else if (namespace === "us/nj") {
      return 'https://www.njleg.state.nj.us/legislative-roster';
    } else if (namespace === "us/nm") {
      return 'https://www.nmlegis.gov/Members/Legislator_List';
    } else if (namespace === "us/ny") {
      return 'https://nyassembly.gov/mem/';
    } else if (namespace === "us/nc") {
      return 'https://www.ncleg.gov/Members/Biography/';
    } else if (namespace === "us/nd") {
      if (lastTerm.chamber === 'UPPER') {
        return 'https://www.legis.nd.gov/assembly/67-2021/members/senate';
      } else {
        return 'https://www.legis.nd.gov/assembly/67-2021/members/house';
      }
    } else if (namespace === "us/oh") {
      return 'https://www.ohiohouse.gov/members/all';
    } else if (namespace === "us/ok") {
      return 'https://www.okhouse.gov/Members/';
    } else if (namespace === "us/or") {
      return 'https://www.oregonlegislature.gov/legislators-and-staff';
    } else if (namespace === "us/pa") {
      return 'https://www.legis.state.pa.us/cfdocs/legis/home/member_information/house_bio.cfm';
    } else if (namespace === "us/ri") {
      if (lastTerm.chamber === 'UPPER') {
        return 'https://www.rilegislature.gov/senators/';
      } else {
        return 'https://www.rilegislature.gov/representatives/';
      }
    } else if (namespace === "us/sc") {
      return 'https://www.scstatehouse.gov/member.php?chamber=' + (lastTerm.chamber === 'UPPER' ? 'S' : 'H');
    } else if (namespace === "us/sd") {
      return 'https://sdlegislature.gov/Legislators/Profile/Session/2024/' + (lastTerm.chamber === 'UPPER' ? 'Upper' : 'Lower');
    } else if (namespace === "us/tn") {
      return 'https://www.capitol.tn.gov/' + (lastTerm.chamber === 'UPPER' ? 'senate' : 'house') + '/members/';
    } else if (namespace === "us/tx") {
      return 'https://capitol.texas.gov/Members/' + (lastTerm.chamber === 'UPPER' ? 'senate' : 'house') + '.aspx?district=' + lastTerm.district;
    } else if (namespace === "us/ut") {
      return 'https://le.utah.gov/asp/interim/Main.asp?LegCode=' + this.leg!.id;
    } else if (namespace === "us/vt") {
      return 'https://legislature.vermont.gov/people/';
    } else if (namespace === "us/va") {
      return 'https://whosmy.virginiageneralassembly.gov/';
    } else if (namespace === "us/wa") {
      return 'https://app.leg.wa.gov/rosters/Members/';
    } else if (namespace === "us/dc") {
      return 'https://dccouncil.gov/councilmembers/';
    } else if (namespace === "us/wv") {
      if (lastTerm.chamber === 'UPPER') {
        return 'https://www.wvlegislature.gov/Senate1/roster.cfm';
      } else {
        return 'https://www.wvlegislature.gov/House/roster.cfm';
      }
    } else if (namespace === "us/wi") {
      return 'https://docs.legis.wisconsin.gov/2023/legislators/' + (lastTerm.chamber === 'UPPER' ? 'senate' : 'assembly');
    } else if (namespace === "us/wy") {
      return 'https://www.wyoleg.gov/Legislators';
    } else if (namespace === "us/as") {
      // American Samoa — unknown structure
    } else if (namespace === "us/gu") {
      if (lastTerm.chamber === 'UPPER') {
        return 'https://www.guamlegislature.com/senators/';
      } else {
        // Guam may not have a lower chamber – fallback to homepage
        return 'https://www.guamlegislature.com/';
      }
    } else if (namespace === "us/mp") {
      // URL structure unknown – check NMI legislature portal
    } else if (namespace === "us/pr") {
      if (lastTerm.chamber === 'UPPER') {
        return 'https://senado.pr.gov/senators/';
      } else {
        return 'https://www.tucamarapr.org/dnncamara/web/ComposiciondelaCamara.aspx';
      }
    } else if (namespace === "us/vi") {
      return 'https://www.legvi.org/senators/';
    }

    return null;
  }

  subtitleForLegislator(): string {
    if (this.leg == null) return "";

    // return this.leg?.terms[this.leg?.terms.length - 1].chamber == "HOUSE" ? "House of Representatives" : "Senate";

    let term = this.leg.terms[this.leg.terms.length - 1];

    let label = term.chamber;
    if (term.chamber == "UPPER") {
      label = "Senator";
    } else if (term.chamber == "LOWER") {
      label = "House";
    }

    let extra: string[] = [];
    if (this.config.getNamespace() === "us/congress")
      extra.push(convertStateCodeToName(term.state));
    if (term.chamber == "LOWER")
      extra.push("District " + term.district);

    if (extra.length > 0)
      label += " (" + extra.join(" ") + ")";

    return label;
  }

  legPhotoError(leg: any) {
    if (leg != null) {
      leg.photoError = true;
    }
  }

  getDisplayedColumns(): string[] {
    if (isPlatformBrowser(this._platformId) && window.innerWidth < 480) {
      return ['billName', 'billGrade', "association"];
    } else {
      return this.displayedColumns;
    }
  }

  routeToBill(id: string)
  {
    this.router.navigate(['/bill/' + id.replace("BIL/" + this.config.getNamespace(), "")]);
  }

  describeAssociation(association: BillInteraction): string {
    if (association["@type"] == "LegislatorBillVote") {
      return "Voted " + (association.voteStatus?.toUpperCase() === "AYE" ? "For" : "Against");
    } else {
      return association["@type"].replace("LegislatorBill", "");
    }
  }

  async buildBarChartData() {
    if (this.leg == null || !this.hasValidInterp) return;

    let data: number[] = [];
    let labels: string[] = [];

    data.push(getBenefitToSocietyIssue(this.leg?.interpretation?.issueStats!)[1]);
    labels.push(getBenefitToSocietyIssue(this.leg?.interpretation?.issueStats!)[0]);

    let i = 0;
    for (const [key, value] of Object.entries(this.leg?.interpretation?.issueStats?.stats)
      .filter(kv => kv[0] != "OverallBenefitToSociety")
      .sort((a, b) => (b[1] as number) - (a[1] as number))) {
      data.push(value as number);
      labels.push(key);
    }

    if (isPlatformBrowser(this._platformId) && window.innerWidth < 480) {
      labels = labels.map(l => issueKeyToLabelSmall(l));
    } else {
      labels = labels.map(l => issueKeyToLabel(l));
    }

    // this.barChart.style.maxWidth = 

    this.barChartData.labels = labels;
    this.barChartData.datasets = [{
      data: data,
      label: "",
      backgroundColor: [
        'rgba(255, 99, 132, 0.2)',
        'rgba(255, 159, 64, 0.2)',
        'rgba(255, 205, 86, 0.2)',
        'rgba(75, 192, 192, 0.2)',
        'rgba(54, 162, 235, 0.2)',
        'rgba(153, 102, 255, 0.2)',
        '#FFF8DC',
        'rgba(255, 99, 132, 0.2)',
        'rgba(255, 159, 64, 0.2)'
      ],
      borderColor: [
        'rgb(255, 99, 132)',
        'rgb(255, 159, 64)',
        'rgb(255, 205, 86)',
        'rgb(75, 192, 192)',
        'rgb(54, 162, 235)',
        'rgb(153, 102, 255)',
        '#FFF8DC',
        'rgb(255, 99, 132)',
        'rgb(255, 159, 64)'
      ],
      borderWidth: 1
    }];

    if (isPlatformBrowser(this._platformId)) {
      await this.waitForImage(document.querySelector('img'));
      window.setTimeout(() => { this.renderBarChart(); }, 10);
    }
  }

  renderBarChart() {
    this.chart = new Chart(
      (this.barChart as any).nativeElement,
      {
        type: 'bar',
        data: this.barChartData,
        options: this.barChartOptions
        // plugins: [ChartDataLabels, floatingLabelsPlugin],
      }
    );
  }

  waitForImage(imgElem: any) {
    return new Promise(res => {
      if (imgElem.complete) {
        return res(null);
      }
      imgElem.onload = () => res(null);
      imgElem.onerror = () => res(null);
    });
  }

  @HostListener('window:scroll', ['$event'])
  onScroll(e: any) {
    if (this.isRequestingData || this.leg == null) return;

    const el = e.target.documentElement;

    // Trigger when scrolled to bottom
    if (el.offsetHeight + el.scrollTop >= (el.scrollHeight - 200) && this.leg != null) {
      this.fetchInteractions();
    }
  }

  fetchInteractions() {
    this.isRequestingData = true;

    this.page.exclusiveStartKey = this.leg!.interactions!.length - 1;

    this.service.getLegislatorInteractions(this.legId!, this.page).then(page => {
      this.leg!.interactions!.push(...page.data[0]);
      this.refreshBillData();
      this.hasMoreData = page.hasMoreData;
    }).finally(() => {
      this.isRequestingData = false;
    });
  }

  togglePage(
    index: PageIndex,
    sortKey: string | undefined = undefined,
    menuTrigger: MatMenuTrigger | undefined = undefined,
    event: Event | undefined = undefined
  ) {
    if (index === "ObjectsByImpactAbs" && this.page.index === 'ObjectsByImpactAbs') {
      this.page.index = "ObjectsByImpact";
      this.page.ascending = false;
    } else if (index === "ObjectsByImpact" && this.page.index === 'ObjectsByImpact' && this.page.ascending) {
      this.page.index = "ObjectsByImpactAbs";
      this.page.ascending = false;
    } else if (index === "ObjectsByRatingAbs" && this.page.index === 'ObjectsByRatingAbs') {
      this.page.index = "ObjectsByRating";
      this.page.ascending = false;
    } else if (index === "ObjectsByRating" && this.page.index === 'ObjectsByRating' && this.page.ascending) {
      this.page.index = "ObjectsByRatingAbs";
      this.page.ascending = false;
    } else if (index === "ObjectsByHot") {
      if (this.page.index === "ObjectsByHot" && !this.page.ascending) return;
      this.page.ascending = false;
      this.page.index = index;
    } else {
      this.page.ascending = index === this.page.index && sortKey === this.page.sortKey ? !this.page.ascending : false;
      this.page.index = index;
    }

    this.page.exclusiveStartKey = undefined;
    this.hasMoreData = true;
    this.page.sortKey = sortKey;
  
    this.leg!.interactions = [];

    let routeIndex = "";
    if (sortKey) {
      routeIndex = sortKey;
    } else {
      routeIndex = this.page.index.replace("Objects", "").toLowerCase();
    }

    this.router.navigate([], { fragment: `sort=${routeIndex}&ascending=${this.page.ascending}`, queryParamsHandling: 'merge', });
  
    // Fetch new interactions
    this.fetchInteractions();
  
    if (event && menuTrigger) {
      event.stopPropagation();
      const overlayDiv = document.querySelector('.cdk-overlay-connected-position-bounding-box');
      if (overlayDiv) overlayDiv.classList.add('closing');
      setTimeout(() => {
        if (overlayDiv) overlayDiv.classList.remove('closing');
        if (overlayDiv) overlayDiv.classList.add('hidden');
        menuTrigger!.closeMenu();
        setTimeout(() => {
          if (overlayDiv) overlayDiv.classList.remove('hidden');
        }, 500);
      }, 300);
    }
  }
  
}
