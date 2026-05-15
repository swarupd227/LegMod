import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StageChip } from './StageChip';

describe('StageChip', () => {
  it('renders the stage letter when current', () => {
    render(<StageChip stage="A" state="current" />);
    expect(screen.getByText('A')).toBeInTheDocument();
  });

  it('renders the stage letter when pending', () => {
    render(<StageChip stage="C" state="pending" />);
    expect(screen.getByText('C')).toBeInTheDocument();
  });

  it('hides the letter and shows the check icon when passed', () => {
    render(<StageChip stage="B" state="passed" />);
    expect(screen.queryByText('B')).not.toBeInTheDocument();
  });

  it('exposes a useful default aria-label per state', () => {
    const { rerender } = render(<StageChip stage="A" state="current" />);
    expect(screen.getByLabelText('Stage A in progress')).toBeInTheDocument();

    rerender(<StageChip stage="A" state="passed" />);
    expect(screen.getByLabelText('Stage A passed')).toBeInTheDocument();

    rerender(<StageChip stage="A" state="pending" />);
    expect(screen.getByLabelText('Stage A')).toBeInTheDocument();
  });

  it('honours an explicit aria-label override', () => {
    render(<StageChip stage="X" state="current" ariaLabel="Custom label" />);
    expect(screen.getByLabelText('Custom label')).toBeInTheDocument();
  });

  it('applies SOAP track tints by default', () => {
    render(<StageChip stage="A" state="current" />);
    const chip = screen.getByLabelText(/Stage A/);
    expect(chip.className).toMatch(/bg-brand-50/);
  });

  it('applies UPLIFT track tints when track="UPLIFT"', () => {
    render(<StageChip stage="A" state="current" track="UPLIFT" />);
    const chip = screen.getByLabelText(/Stage A/);
    expect(chip.className).toMatch(/bg-agent-50/);
  });

  it('applies the passed colour scheme regardless of track', () => {
    render(<StageChip stage="A" state="passed" track="UPLIFT" />);
    const chip = screen.getByLabelText(/Stage A passed/);
    expect(chip.className).toMatch(/bg-ok/);
  });

  it('changes dimensions per size prop', () => {
    const { rerender } = render(<StageChip stage="A" size="sm" />);
    expect(screen.getByLabelText(/Stage A/).className).toMatch(/h-6 w-6/);

    rerender(<StageChip stage="A" size="lg" />);
    expect(screen.getByLabelText(/Stage A/).className).toMatch(/h-9 w-9/);
  });
});
