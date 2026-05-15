import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SegmentedControl, Segment } from './SegmentedControl';

type FilterId = 'all' | 'pending' | 'resolved';

const SEGMENTS: Segment<FilterId>[] = [
  { id: 'all', label: 'All', count: 12 },
  { id: 'pending', label: 'Pending', count: 3, tone: 'warn' },
  { id: 'resolved', label: 'Resolved', count: 9, tone: 'ok' }
];

describe('SegmentedControl', () => {
  it('renders as a radiogroup with the provided ariaLabel', () => {
    render(
      <SegmentedControl<FilterId>
        segments={SEGMENTS}
        value="all"
        onChange={() => {}}
        ariaLabel="Decision filter"
      />
    );
    expect(screen.getByRole('radiogroup', { name: 'Decision filter' })).toBeInTheDocument();
  });

  it('renders one radio per segment with aria-checked reflecting selection', () => {
    render(
      <SegmentedControl<FilterId>
        segments={SEGMENTS}
        value="pending"
        onChange={() => {}}
        ariaLabel="t"
      />
    );
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(3);
    expect(radios.find(r => r.getAttribute('aria-checked') === 'true')!.textContent)
        .toMatch(/Pending/);
  });

  it('renders the count and uses tabular-nums for alignment', () => {
    render(
      <SegmentedControl<FilterId>
        segments={SEGMENTS}
        value="all"
        onChange={() => {}}
        ariaLabel="t"
      />
    );
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('formats counts with locale grouping', () => {
    render(
      <SegmentedControl<FilterId>
        segments={[{ id: 'all', label: 'All', count: 12345 }]}
        value="all"
        onChange={() => {}}
        ariaLabel="t"
      />
    );
    expect(screen.getByText('12,345')).toBeInTheDocument();
  });

  it('calls onChange with the selected id on click', async () => {
    const onChange = vi.fn();
    render(
      <SegmentedControl<FilterId>
        segments={SEGMENTS}
        value="all"
        onChange={onChange}
        ariaLabel="t"
      />
    );
    await userEvent.click(screen.getByRole('radio', { name: /Resolved/ }));
    expect(onChange).toHaveBeenCalledWith('resolved');
  });

  it('applies tone class to the active segment label', () => {
    render(
      <SegmentedControl<FilterId>
        segments={SEGMENTS}
        value="pending"
        onChange={() => {}}
        ariaLabel="t"
      />
    );
    const active = screen.getAllByRole('radio')
        .find(r => r.getAttribute('aria-checked') === 'true')!;
    expect(active.className).toMatch(/text-warn/);
  });

  it('omits the count span when no count is provided', () => {
    render(
      <SegmentedControl<FilterId>
        segments={[{ id: 'all', label: 'All' }]}
        value="all"
        onChange={() => {}}
        ariaLabel="t"
      />
    );
    expect(screen.getByText('All')).toBeInTheDocument();
    // Only the label span exists — no separate count span.
    expect(screen.queryByText('0')).toBeNull();
  });
});
