import { Component, Inject, PLATFORM_ID, ViewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { Chart, ChartConfiguration, BarController, CategoryScale, LinearScale, BarElement, Tooltip} from 'chart.js'
import ChartDataLabels from 'chartjs-plugin-datalabels';
import { AppService } from '../app.service';
import { Meta, Title } from '@angular/platform-browser';
import convertStateCodeToName, { Bill, colorForGrade, getBenefitToSocietyIssue, gradeForStats, issueKeyToLabel, issueKeyToLabelSmall, Legislator, SessionStats } from '../model';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { MatCardModule } from '@angular/material/card'; 
import { HttpClient } from '@angular/common/http';
import { MatTableModule } from '@angular/material/table';
import { gradeForLegislator, subtitleForLegislator, descriptionForLegislator, upForReelection } from '../legislators';
import { gradeForBill, subtitleForBill, descriptionForBill } from '../bills';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatButtonModule } from '@angular/material/button';
import { ConfigService } from '../config.service';
import { HeaderComponent } from '../header/header.component';

Chart.register(BarController, CategoryScale, LinearScale, BarElement, ChartDataLabels, Tooltip);

@Component({
  selector: 'sessionstats',
  standalone: true,
  imports: [HeaderComponent, CommonModule, MatCardModule, MatTableModule, MatButtonToggleModule, MatButtonModule, RouterModule],
  providers: [AppService, HttpClient],
  templateUrl: './sessionstats.component.html',
  styleUrl: './sessionstats.component.scss'
})
export class SessionStatsComponent {

  @ViewChild("barChart") barChart!: HTMLCanvasElement;

  public party: "REPUBLICAN" | "DEMOCRAT" | "INDEPENDENT" = "REPUBLICAN";

  public sort: "legislators" | "bills" = "legislators";

  public ascending: boolean = false;

  public stats?: SessionStats;

  public loading: boolean = true;

  public isRequestingData: boolean = false;

  private chart: Chart | undefined;

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

  constructor(public config: ConfigService, private meta: Meta, private service: AppService, private route: ActivatedRoute, private router: Router, @Inject(PLATFORM_ID) private _platformId: Object, private titleService: Title) { }

  ngOnInit(): void {
    this.route.params.subscribe(newParams => {
      let party = this.route.snapshot.paramMap.get('party') as string;
      if (party != null) {
        this.party = party.toUpperCase() as "REPUBLICAN" | "DEMOCRAT" | "INDEPENDENT";
      }

      let routeSort = this.route.snapshot.paramMap.get('sort') as string;
      if (routeSort != null) {
        if (routeSort == 'best-legislators') {
          this.sort = "legislators";
          this.ascending = false;
        } else if (routeSort == 'worst-legislators') {
          this.sort = "legislators";
          this.ascending = true;
        } else if (routeSort == 'important-bills') {
          this.sort = "bills";
          this.ascending = false;
        } else if (routeSort == 'worst-bills') {
          this.sort = "bills";
          this.ascending = true;
        }
      }

      this.buildBarChartData();
    }); 

    if (this.stats == null) {
      this.service.getSessionStats().then(stats => {
        this.stats = stats;
        this.loading = false;
  
        if (stats == null) { return; }
  
        this.updateMetaTags();
  
        this.buildBarChartData();
      });
    }
  }

  updateMetaTags(): void {
    if (this.stats == null) return;

    let year = this.config.getYear();

    let pageTitle = "Stats - PoliScore: AI Political Rating Service";
    if (this.stats?.session.namespace === 'us/congress')
      pageTitle = this.stats!.session.code + "th Congress " + pageTitle;
    else
      pageTitle = (this.stats!.session.endDate as any as string).split("-")[0] + " " + convertStateCodeToName(this.stats.session.namespace.split("/")[1]) + " Legislature " + pageTitle;

    const pageDescription = this.config.appDescription();
    const pageUrl = "https://poliscore.us/" + year + "/Party";
    const imageUrl = 'https://poliscore.us/' + year + '/images/poliscore-word-whitebg.png';

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

  getInterpretation() {
    if (this.party === "REPUBLICAN") {
      return this.stats?.republican;
    } else if (this.party === "DEMOCRAT") {
      return this.stats?.democrat;
    } else {
      return this.stats?.independent;
    }
  }

  toggleSort(index: "legislators" | "bills") {
    this.ascending = index === this.sort ? !this.ascending : false;
    this.sort = index;

    let routeIndex;
    if (index == "legislators") { routeIndex = !this.ascending ? "best-legislators" : "worst-legislators"; }
    if (index == "bills") { routeIndex = !this.ascending ? "important-bills" : "worst-bills"; }

    this.router.navigate(['/congress/' + this.party.toLowerCase() + '/' +  routeIndex]);;
  }

  public getData() {
    let routeIndex = "";
    if (this.sort == "legislators") { routeIndex = !this.ascending ? "bestLegislators" : "worstLegislators"; }
    if (this.sort == "bills") { routeIndex = !this.ascending ? "mostImportantBills" : "worstBills"; }

    return ((this.stats! as any)[this.party.toLowerCase()] as any)[routeIndex];
  }

  async buildBarChartData() {
    if (this.party == null || this.stats == null) return;

    let partyStats = (this.stats as any)[this.party.toLowerCase()].stats;

    let data: number[] = [];
    let labels: string[] = [];

    data.push(getBenefitToSocietyIssue(partyStats)[1]);
    labels.push(getBenefitToSocietyIssue(partyStats)[0]);

    let i = 0;
    for (const [key, value] of Object.entries(partyStats.stats)
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
      // await this.waitForImage(document.querySelector('img'));
      // window.setTimeout(() => { this.renderBarChart(); }, 10);
      this.renderBarChart();
    }
  }

  renderBarChart() {
    if (this.chart != null) {
      this.chart.destroy();
    }

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

  colorForGrade(grade: any): any { return colorForGrade(grade); }
  gradeForParty(): any { return gradeForStats(this.getInterpretation()?.stats); }
  gradeForLegislator(leg: Legislator): string { return gradeForStats(leg.interpretation?.issueStats!); }
  subtitleForLegislator(leg: Legislator) { return subtitleForLegislator(leg); }
  descriptionForLegislator(leg: Legislator) { return descriptionForLegislator(leg, isPlatformBrowser(this._platformId) && window.innerWidth < 480); }
  upForReelection(leg: Legislator): any { return upForReelection(leg); }
  gradeForBill(bill: Bill): string { return gradeForBill(bill); }
  subtitleForBill(bill: Bill) { return subtitleForBill(bill); }
  descriptionForBill(bill: Bill) { return descriptionForBill(bill); }
}
