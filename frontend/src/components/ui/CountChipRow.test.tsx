import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CountChipRow } from './CountChipRow';

describe('CountChipRow', () => {
  it('renders nothing when chips is empty', () => {
    const { container } = render(<CountChipRow chips={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders one <li> per chip with label + value', () => {
    render(
      <CountChipRow
        chips={[
          { label: 'pending', value: 3, tone: 'warn' },
          { label: 'resolved', value: 8, tone: 'ok' }
        ]}
      />
    );
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByText('pending')).toBeInTheDocument();
    expect(screen.getByText('resolved')).toBeInTheDocument();
  });

  it('formats numeric values with locale grouping', () => {
    render(<CountChipRow chips={[{ label: 'envelopes', value: 12345 }]} />);
    expect(screen.getByText('12,345')).toBeInTheDocument();
  });

  it('renders string values verbatim', () => {
    render(<CountChipRow chips={[{ label: 'status', value: 'live' }]} />);
    expect(screen.getByText('live')).toBeInTheDocument();
  });

  it('applies the tone class to the value, not the label', () => {
    render(<CountChipRow chips={[{ label: 'red', value: 7, tone: 'err' }]} />);
    const value = screen.getByText('7');
    expect(value.className).toMatch(/text-err/);
    expect(screen.getByText('red').className).not.toMatch(/text-err/);
  });

  it('falls back to neutral tone when none specified', () => {
    render(<CountChipRow chips={[{ label: 'x', value: 1 }]} />);
    expect(screen.getByText('1').className).toMatch(/text-fg-1/);
  });

  it('uses provided ariaLabel on the list', () => {
    render(
      <CountChipRow
        chips={[{ label: 'x', value: 1 }]}
        ariaLabel="Recipe counts"
      />
    );
    expect(screen.getByRole('list', { name: 'Recipe counts' })).toBeInTheDocument();
  });

  it('exposes the hint via the title attribute on each <li>', () => {
    render(
      <CountChipRow
        chips={[{ label: 'pending', value: 1, hint: '1 awaiting decision' }]}
      />
    );
    const li = screen.getByRole('listitem');
    expect(li.getAttribute('title')).toBe('1 awaiting decision');
  });
});
