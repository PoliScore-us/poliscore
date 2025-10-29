import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ExpertPreviewComponent } from './expert-preview.component';

describe('ExpertPreviewComponent', () => {
  let component: ExpertPreviewComponent;
  let fixture: ComponentFixture<ExpertPreviewComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ExpertPreviewComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ExpertPreviewComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
