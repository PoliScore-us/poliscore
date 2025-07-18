import { Component, HostListener, Inject, OnInit, PLATFORM_ID } from '@angular/core';
import { AppService } from '../app.service';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { Bill, BillInterpretation, colorForGrade, getBenefitToSocietyIssue, gradeForRating, gradeForStats, issueKeyToLabel, issueKeyToLabelSmall, PressInterpretation } from '../model';
import { MatCardModule } from '@angular/material/card';
import { CommonModule, isPlatformBrowser, isPlatformServer } from '@angular/common';
import { HttpClient, HttpHandler } from '@angular/common/http';
import ChartDataLabels from 'chartjs-plugin-datalabels';
import { Chart, ChartConfiguration, BarController, CategoryScale, LinearScale, BarElement, Tooltip} from 'chart.js'
import { Meta, Title } from '@angular/platform-browser';
import { MatButtonModule } from '@angular/material/button';
import { ConfigService } from '../config.service';
import { HeaderComponent } from '../header/header.component';
import { DisclaimerComponent } from '../disclaimer/disclaimer.component';
import { MatTableModule } from '@angular/material/table';
import {MatTooltipModule} from '@angular/material/tooltip';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { shortNameForBill } from '../bills';

Chart.register(BarController, CategoryScale, LinearScale, BarElement, ChartDataLabels, Tooltip);

@Component({
  selector: 'bill',
  standalone: true,
  imports: [MatTooltipModule, MatTableModule, DisclaimerComponent, HeaderComponent, MatCardModule, CommonModule, CommonModule, RouterModule, MatButtonModule],
  providers: [AppService, HttpClient],
  templateUrl: './bill.component.html',
  styleUrl: './bill.component.scss'
})
export class BillComponent implements OnInit {

  public bill?: Bill;

  public billId?: string;

  public loading: boolean = true;

  public isSmallScreen = false;

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
    scales: {
      x: {
        min: -100,
        max: 100
      },
      // y: {ticks: {mirror: true, crossAlign: "center", align: "center", z: 1}}
      y: { ticks: { display: false } }
    }
  };

  constructor(private sanitizer: DomSanitizer, public config: ConfigService, private meta: Meta, private service: AppService, private route: ActivatedRoute, private router: Router, @Inject(PLATFORM_ID) private _platformId: Object, private titleService: Title) { }

  @HostListener('window:resize', ['$event'])
  onResize() {
    // Check screen width on resize
    this.isSmallScreen = window.innerWidth < 600;
  }

  ngOnInit(): void {
    if (isPlatformBrowser(this._platformId))
      this.isSmallScreen = window.innerWidth < 600;

    this.billId = (this.route.snapshot.paramMap.get('id') as string);
    if (!this.billId.startsWith("BIL/" + this.config.getNamespace())) {
      this.billId = this.config.pathToBillId(this.billId);
    }

    this.service.getBill(this.billId).then(bill => {
      this.bill = bill;

      if (bill == null)
        throw new Error("Backend did not return a bill for [" + this.billId + "]");

      if (bill.interpretation == null || bill.interpretation.longExplain == null || bill.interpretation.longExplain.length == 0
        || bill.interpretation.shortExplain == null || bill.interpretation.shortExplain.length == 0
        || bill.interpretation.issueStats == null || bill.interpretation.issueStats.stats == null || bill.interpretation.issueStats.stats['OverallBenefitToSociety'] == null) {
        throw new Error("Invalid interpretation for bill " + this.billId);
      }

      this.loading = false;
      this.updateMetaTags();
      this.buildBarChartData();
    });
  }

  getBillTooltip(): string {
    if (!this.bill || !this.bill.name || this.bill!.name.length <= 125 || this.getBillName()!.trim().toLowerCase() === this.bill!.name.trim().toLowerCase()) return '';

    return 'This bill\'s name was shortened by AI, for your convenience. The official bill title is: \n\n' + this.bill?.name;
  }
  
  getBillDate() {
    if (this.bill?.lastActionDate != null) {
      return this.bill.lastActionDate;
    } else {
      return this.bill?.introducedDate;
    }
  }

  officialUrlForBill(): string | undefined {
    if (this.bill == null) return;

    let namespace = this.bill.id?.split("/")[1] + "/" + this.bill.id?.split("/")[2];
    let sessionCode = this.bill.id?.split("/")[3];
    let billSlug = this.bill.type + this.bill.number;

    if (namespace === "us/congress") {
      return 'https://www.congress.gov/bill/' + sessionCode + '-congress/' + this.getCongressGovBillType() + '/' + this.bill.number;
    } else if (namespace === "us/alabama") {
      return 'https://alisondb.legislature.state.al.us/Alison/Legislation/ViewLegislation.aspx?OID=' + this.bill.id;
    } else if (namespace === "us/alaska") {
      return 'http://www.akleg.gov/basis/Bill/Detail/' + sessionCode + '/' + billSlug;
    } else if (namespace === "us/arizona") {
      return 'https://apps.azleg.gov/BillStatus/BillOverview/' + billSlug;
    } else if (namespace === "us/arkansas") {
      return 'https://www.arkleg.state.ar.us/Bills/Detail?id=' + this.bill.id;
    } else if (namespace === "us/california") {
      return 'https://leginfo.legislature.ca.gov/faces/billNavClient.xhtml?bill_id=' + sessionCode + billSlug.toLowerCase();
    } else if (namespace === "us/colorado") {
      return 'https://leg.colorado.gov/bills/' + billSlug.toLowerCase();
    } else if (namespace === "us/connecticut") {
      return 'https://www.cga.ct.gov/asp/cgabillstatus/cgabillstatus.asp?selBillType=Bill&bill_num=' + billSlug;
    } else if (namespace === "us/delaware") {
      return 'https://legis.delaware.gov/BillDetail?LegislationId=' + this.bill.id;
    } else if (namespace === "us/florida") {
      return 'https://www.flsenate.gov/Session/Bill/2024/' + billSlug;
    } else if (namespace === "us/georgia") {
      return 'https://www.legis.ga.gov/legislation/' + this.bill.id;
    } else if (namespace === "us/hawaii") {
      const type = this.bill.type.match(/[A-Z]+/)?.[0];
      const number = this.bill.number;
      return 'https://www.capitol.hawaii.gov/measure_indiv.aspx?billtype=' + type + '&billnumber=' + number + '&year=' + sessionCode;
    } else if (namespace === "us/idaho") {
      return 'https://legislature.idaho.gov/sessioninfo/billbookmark/?yr=' + sessionCode + '&bn=' + billSlug;
    } else if (namespace === "us/illinois") {
      return 'https://ilga.gov/legislation/BillStatus.asp?DocNum=' + this.bill.number + '&GA=' + sessionCode;
    } else if (namespace === "us/indiana") {
      return 'https://iga.in.gov/legislative/' + sessionCode + '/bills/' + billSlug.toLowerCase();
    } else if (namespace === "us/iowa") {
      return 'https://www.legis.iowa.gov/legislation/BillBook?ga=' + sessionCode + '&ba=' + billSlug.toUpperCase();
    } else if (namespace === "us/kansas") {
      return 'http://kslegislature.org/li/b' + sessionCode + '/bills/' + billSlug.toLowerCase() + '/';
    } else if (namespace === "us/kentucky") {
      return 'https://apps.legislature.ky.gov/record/' + sessionCode + '/' + billSlug.toLowerCase() + '.html';
    } else if (namespace === "us/louisiana") {
      return 'https://legis.la.gov/Legis/BillInfo.aspx?i=' + this.bill.id;
    } else if (namespace === "us/maine") {
      return 'https://legislature.maine.gov/LawMakerWeb/summary.asp?ID=' + this.bill.id;
    } else if (namespace === "us/maryland") {
      return 'https://mgaleg.maryland.gov/mgawebsite/Legislation/Details/' + billSlug.toUpperCase();
    } else if (namespace === "us/massachusetts") {
      return 'https://malegislature.gov/Bills/' + sessionCode + '/' + billSlug.toUpperCase();
    } else if (namespace === "us/michigan") {
      return 'https://legislature.mi.gov/documents/' + sessionCode + '-' + (Number(sessionCode) + 1) + '/billhtm/' + billSlug.toUpperCase() + '.htm';
    } else if (namespace === "us/minnesota") {
      return 'https://www.revisor.mn.gov/bills/text.php?number=' + billSlug.toUpperCase();
    } else if (namespace === "us/mississippi") {
      return 'http://billstatus.ls.state.ms.us/2024/pdf/history/' + billSlug.charAt(0).toUpperCase() + '/' + billSlug.toUpperCase() + '.xml';
    } else if (namespace === "us/missouri") {
      return 'https://house.mo.gov/Bill.aspx?bill=' + billSlug.toUpperCase() + '&year=' + sessionCode + '&code=R';
    } else if (namespace === "us/montana") {
      return 'https://leg.mt.gov/bills/' + sessionCode + '/billhtml/' + billSlug.toLowerCase() + '.htm';
    } else if (namespace === "us/nebraska") {
      return 'https://nebraskalegislature.gov/bills/view_bill.php?DocumentID=' + this.bill.id;
    } else if (namespace === "us/nevada") {
      return 'https://www.leg.state.nv.us/App/NELIS/REL/' + sessionCode + '/' + billSlug.toUpperCase();
    } else if (namespace === "us/new_hampshire") {
      return 'https://www.gencourt.state.nh.us/bill_status/billinfo.aspx?id=' + this.bill.id;
    } else if (namespace === "us/new_jersey") {
      return 'https://www.njleg.state.nj.us/bill-search/' + billSlug.toUpperCase();
    } else if (namespace === "us/new_mexico") {
      return 'https://www.nmlegis.gov/Legislation/Legislation?Chamber=H&LegType=B&LegNo=' + this.bill.number + '&year=' + sessionCode;
    } else if (namespace === "us/new_york") {
      return 'https://nyassembly.gov/leg/?default_fld=&bn=' + billSlug.toUpperCase();
    } else if (namespace === "us/north_carolina") {
      return 'https://www.ncleg.gov/BillLookup/' + billSlug.toUpperCase();
    } else if (namespace === "us/north_dakota") {
      return 'https://www.ndlegis.gov/assembly/' + sessionCode + '/bill/' + billSlug.toLowerCase();
    } else if (namespace === "us/ohio") {
      return 'https://www.legislature.ohio.gov/legislation/legislation-summary?id=' + this.bill.id;
    } else if (namespace === "us/oklahoma") {
      return 'http://www.oklegislature.gov/BillInfo.aspx?Bill=' + billSlug.toUpperCase();
    } else if (namespace === "us/oregon") {
      return 'https://olis.oregonlegislature.gov/liz/' + sessionCode + '/Measures/Overview/' + billSlug.toUpperCase();
    } else if (namespace === "us/pennsylvania") {
      return 'https://www.legis.state.pa.us/cfdocs/billinfo/billinfo.cfm?syear=' + sessionCode + '&body=' + billSlug.charAt(0) + '&type=B&bn=' + this.bill.number;
    } else if (namespace === "us/rhode_island") {
      return 'https://status.rilegislature.gov/bills/' + sessionCode + '/' + billSlug.toUpperCase();
    } else if (namespace === "us/south_carolina") {
      return 'https://www.scstatehouse.gov/billsearch.php?billnumbers=' + billSlug.toUpperCase();
    } else if (namespace === "us/south_dakota") {
      return 'https://sdlegislature.gov/Session/Bills/' + billSlug.toUpperCase();
    } else if (namespace === "us/tennessee") {
      return 'https://wapp.capitol.tn.gov/apps/BillInfo/default.aspx?BillNumber=' + billSlug.toUpperCase();
    } else if (namespace === "us/texas") {
      return 'https://capitol.texas.gov/BillLookup/History.aspx?LegSess=' + sessionCode + '&Bill=' + billSlug.toUpperCase();
    } else if (namespace === "us/utah") {
      return 'https://le.utah.gov/~' + sessionCode + '/bills/static/' + billSlug.toUpperCase() + '.html';
    } else if (namespace === "us/vermont") {
      return 'https://legislature.vermont.gov/bill/status/' + billSlug.toUpperCase();
    } else if (namespace === "us/virginia") {
      return 'https://lis.virginia.gov/cgi-bin/legp604.exe?' + sessionCode + '+' + billSlug.toUpperCase();
    } else if (namespace === "us/washington") {
      return 'https://app.leg.wa.gov/billsummary?BillNumber=' + this.bill.number + '&Year=' + sessionCode;
    } else if (namespace === "us/washington_dc") {
      return 'https://lims.dccouncil.gov/Legislation/' + billSlug.toUpperCase();
    } else if (namespace === "us/west_virginia") {
      return 'https://www.wvlegislature.gov/Bill_Status/bills_history.cfm?INPUT=' + billSlug.toUpperCase();
    } else if (namespace === "us/wisconsin") {
      return 'https://docs.legis.wisconsin.gov/' + sessionCode + '/proposals/' + billSlug.toLowerCase();
    } else if (namespace === "us/wyoming") {
      return 'https://www.wyoleg.gov/Legislation/' + sessionCode + '/' + billSlug.toUpperCase();
    } else if (namespace === "us/american_samoa") {
      // URL structure unknown – may require local government portal
    } else if (namespace === "us/guam") {
      return 'https://www.guamlegislature.com/bills/';
    } else if (namespace === "us/northern_mariana_islands") {
      // Unknown – check local legislative site
    } else if (namespace === "us/puerto_rico") {
      return 'https://sutra.oslpr.org/osl/esutracav/Buscar.aspx?RC=' + billSlug.toUpperCase();
    } else if (namespace === "us/virgin_islands") {
      return 'https://www.legvi.org/bill-tracking/';
    }

    return undefined;
  }

  updateMetaTags(): void {
    let billId = this.bill?.id ?? this.bill?.billId!;
    let billSession = parseInt(billId.split("/")[3]);
    let year = this.config.congressToYear(billSession);

    const pageTitle = this.bill!.name + " - Bill - PoliScore: AI Political Rating Service";
    const pageDescription = this.gradeForBill() + " (" + this.bill?.cosponsors.length + " cosponsors) - " + this.bill!.interpretation.shortExplain!.replace(/[\r\n]/g, '');
    const pageUrl = `https://poliscore.us` + this.config.billIdToAbsolutePath(billId);
    const imageUrl = 'https://poliscore.us/' + year + '/images/billonly.png';

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

  getCongressGovBillType() {
    if (this.bill?.type == "SJRES") {
      return "senate-joint-resolution";
    } else if (this.bill?.type == "HR") {
      return "house-bill";
    } else if (this.bill?.type == "HJRES") {
        return "house-joint-resolution";
    } else if (this.bill?.type == "S") {
        return "senate-bill";
    } else {
      return "";
    }
  }

  getBillName() { return this.bill == null ? "" : shortNameForBill(this.bill!); }
  gradeForBill(): string { return this.gradeForInterp(this.bill?.interpretation!); }
  gradeForInterp(interp: BillInterpretation) { return gradeForStats(interp?.issueStats!); }
  // gradeForPressInterp(interp: PressInterpretation) { return interp!.sentiment; }
  gradeForPressInterp(interp: PressInterpretation) { return interp!.sentiment >= 10 ? "Positive" : (interp!.sentiment < 0 ? "Negative" : "Mixed") }

  colorForGrade(grade: string): string { return colorForGrade(this.gradeForBill()); }

  public getCosponsorSmall() {
    var plural = (this.bill!.cosponsors.length > 1 ? "s" : "");

    // if (this.bill!.cosponsors.length <= 2)
    //   return "Cosponsor" + plural + ": " + this.bill?.cosponsors.map(s => s.name).join(", ");
    // else
    return this.bill!.cosponsors.length + " cosponsor" + plural;
  }

  public getCosponsorLarge() {
    var plural = (this.bill!.cosponsors.length > 1 ? "s" : "");

    return "Cosponsor" + plural + ":\n\n" + this.bill?.cosponsors.map(s => "- <a href='" + this.config.legislatorIdToAbsolutePath(s.legislatorId) + "'>" + s.name.official_full + "</a>").join("\n");
  }

  getDisplayedColumns(): string[] {
    if (isPlatformBrowser(this._platformId) && window.innerWidth < 480) {
      return ['author', 'title', 'grade'];
    } else {
      return ['author', 'title', 'grade', "shortReport"];
    }
  }

  isNonSafari() {
    return !(navigator.userAgent.includes('Safari') && !navigator.userAgent.includes('Chrome'));
  }

  openOrigin(url: string) {
    window.location.href = url;
  }

  async buildBarChartData() {
    let data: number[] = [];
    let labels: string[] = [];

    data.push(getBenefitToSocietyIssue(this.bill?.interpretation?.issueStats!)[1]);
    labels.push(getBenefitToSocietyIssue(this.bill?.interpretation?.issueStats!)[0]);

    let i = 0;
    for (const [key, value] of Object.entries(this.bill?.interpretation?.issueStats?.stats)
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
      window.setTimeout(() => {
        new Chart(
          document.getElementById('barChart') as any,
          {
            type: 'bar',
            data: this.barChartData,
            options: this.barChartOptions
          }
        );
      }, 10);
    }
  }

}
