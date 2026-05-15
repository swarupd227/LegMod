import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusBadge, StatusDot } from './StatusBadge';

describe('StatusBadge', () => {
  it('renders the label', () => {
    render(<StatusBadge label="accepted" tone="ok" />);
    expect(screen.getByText('accepted')).toBeInTheDocument();
  });

  it('applies the requested tone class', () => {
    render(<StatusBadge label="warn" tone="warn" />);
    const span = screen.getByText('warn');
    expect(span.className).toMatch(/badge-warn/);
  });

  it('uses badge-mono by default and skips it when mono=false', () => {
    const { rerender } = render(<StatusBadge label="x" tone="brand" />);
    expect(screen.getByText('x').className).toMatch(/badge-mono/);

    rerender(<StatusBadge label="x" tone="brand" mono={false} />);
    expect(screen.getByText('x').className).not.toMatch(/badge-mono/);
  });

  it('does not render a dot by default', () => {
    const { container } = render(<StatusBadge label="x" tone="brand" />);
    expect(container.querySelector('.dot')).toBeNull();
  });

  it('renders a dot when dot=true', () => {
    const { container } = render(<StatusBadge label="x" tone="ok" dot />);
    expect(container.querySelector('.dot')).not.toBeNull();
  });

  it('adds the pulse class only when pulse=true and dot is shown', () => {
    const { container, rerender } = render(<StatusBadge label="x" tone="ok" dot />);
    expect(container.querySelector('.dot-pulse')).toBeNull();

    rerender(<StatusBadge label="x" tone="ok" dot pulse />);
    expect(container.querySelector('.dot-pulse')).not.toBeNull();
  });

  it('marks the dot as decorative for screen readers', () => {
    const { container } = render(<StatusBadge label="x" tone="ok" dot />);
    expect(container.querySelector('.dot')!.getAttribute('aria-hidden')).toBe('true');
  });

  it('forwards a custom className', () => {
    render(<StatusBadge label="x" tone="brand" className="ml-2 extra-class" />);
    const span = screen.getByText('x');
    expect(span.className).toMatch(/extra-class/);
  });
});

describe('StatusDot', () => {
  it('renders an aria-hidden dot with the tone color', () => {
    const { container } = render(<StatusDot tone="err" />);
    const dot = container.querySelector('.dot')!;
    expect(dot).not.toBeNull();
    expect(dot.className).toMatch(/bg-err/);
    expect(dot.getAttribute('aria-hidden')).toBe('true');
  });

  it('adds dot-pulse when pulse=true', () => {
    const { container } = render(<StatusDot tone="warn" pulse />);
    expect(container.querySelector('.dot-pulse')).not.toBeNull();
  });
});
