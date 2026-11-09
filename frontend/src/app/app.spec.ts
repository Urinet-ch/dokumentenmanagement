import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { TEXTE } from './gemeinsam/texte.de-CH';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [App] }).compileComponents();
  });

  it('zeigt den Titel', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h1')?.textContent).toContain(TEXTE.appTitel);
  });
});
