import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MetricTile } from './MetricTile';
import type { IconType } from './icons';

describe('MetricTile', () => {
  it('renders label and value', () => {
    render(<MetricTile label="Operations" value={42} />);
    expect(screen.getByText('Operations')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('formats numeric values with locale grouping', () => {
    render(<MetricTile label="Envelopes" value={4250} />);
    expect(screen.getByText('4,250')).toBeInTheDocument();
  });

  it('renders string values as-is, no formatting', () => {
    render(<MetricTile label="Status" value="—" />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('renders the optional sub label', () => {
    render(<MetricTile label="Adapters" value={3} sub="2 date" />);
    expect(screen.getByText(/2 date/)).toBeInTheDocument();
  });

  it('hides sub when not provided', () => {
    render(<MetricTile label="X" value={1} />);
    // sub is rendered as a child of the label line — it begins with " · "
    expect(screen.queryByText(/·/)).toBeNull();
  });

  it('applies tone color to the number when accent=true', () => {
    render(<MetricTile label="Red" value={5} tone="err" accent />);
    const num = screen.getByText('5');
    expect(num.className).toMatch(/text-err/);
  });

  it('keeps the default fg-1 colour on the number when accent=false', () => {
    render(<MetricTile label="Brand" value={1} tone="brand" />);
    const num = screen.getByText('1');
    expect(num.className).toMatch(/text-fg-1/);
    expect(num.className).not.toMatch(/text-brand\b/);
  });

  it('renders an icon container when icon prop is supplied', () => {
    // Cast a plain functional component as IconType so we don't pull in a
    // real lucide-react icon just for the smoke check.
    const FakeIcon = ((props: { size?: number }) => (
      <svg data-testid="fake-icon" width={props.size} />
    )) as unknown as IconType;
    render(<MetricTile icon={FakeIcon} label="x" value={1} />);
    expect(screen.getByTestId('fake-icon')).toBeInTheDocument();
  });

  it('omits the icon container when icon prop is not supplied', () => {
    const { container } = render(<MetricTile label="x" value={1} />);
    expect(container.querySelector('svg')).toBeNull();
  });
});
