import { TestBed } from '@angular/core/testing';
import { ONLINE } from './online';

describe('ONLINE', () => {
  afterEach(() => vi.restoreAllMocks());

  it('übernimmt Online- und Offline-Ereignisse', () => {
    let verbunden = true;
    vi.spyOn(window.navigator, 'onLine', 'get').mockImplementation(() => verbunden);
    const online = TestBed.inject(ONLINE);
    expect(online()).toBe(true);

    verbunden = false;
    window.dispatchEvent(new Event('offline'));
    expect(online()).toBe(false);
    verbunden = true;
    window.dispatchEvent(new Event('online'));
    expect(online()).toBe(true);
  });
});
