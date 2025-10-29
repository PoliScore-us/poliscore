import { CommonModule } from '@angular/common';
import {
  Component,
  Input,
  ElementRef,
  OnInit,
  OnDestroy,
  ViewChildren,
  QueryList,
  AfterViewInit
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { SafeHtml } from '@angular/platform-browser';

@Component({
  selector: 'expert-preview',
  standalone: true,
  imports: [CommonModule, MatButtonModule],
  templateUrl: './expert-preview.component.html',
  styleUrl: './expert-preview.component.scss'
})
export class ExpertPreviewComponent {
  @Input() paragraphs: SafeHtml[] = [];
  @Input() isAuthenticated = false;

  readonly maxParagraphs = 3;

  aler(str: string): void {
    alert(str);
  }

  // compute CSS filter for paragraph at index i
  filterForIndex(i: number): string {
    if (this.isAuthenticated) return 'none';

    // First paragraph is fully visible
    if (i === 0) return 'none';

    // Progressively increase blur for later paragraphs
    const blurPx = Math.min(10, i * 2.5);   // 1 → 2.5px, 2 → 5px, 3 → 7.5px
    const opacity = Math.max(0.4, 1 - i * 0.15);

    return `blur(${blurPx}px) opacity(${opacity})`;
  }

  // limit visible paragraphs to 3 for non-authenticated users
  get visibleParagraphs(): SafeHtml[] {
    if (this.isAuthenticated) return this.paragraphs;
    return this.paragraphs.slice(0, this.maxParagraphs);
  }

  // show CTA overlay if unauthenticated
  showSignupOverlay(): boolean {
    return !this.isAuthenticated;
  }
}

