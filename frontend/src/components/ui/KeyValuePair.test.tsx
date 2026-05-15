import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { KeyValuePair, KeyValueList } from './KeyValuePair';

describe('KeyValuePair', () => {
  it('renders the label inside a <dt> and the value inside a <dd>', () => {
    render(<KeyValuePair label="Owner" value="alice" />);
    const dt = screen.getByText('Owner');
    const dd = screen.getByText('alice');
    expect(dt.tagName).toBe('DT');
    expect(dd.tagName).toBe('DD');
  });

  it('uses font-mono on the value when mono=true', () => {
    render(<KeyValuePair label="Path" value="/etc/hosts" mono />);
    const dd = screen.getByText('/etc/hosts');
    expect(dd.className).toMatch(/font-mono/);
  });

  it('does not use font-mono by default', () => {
    render(<KeyValuePair label="Path" value="/etc/hosts" />);
    const dd = screen.getByText('/etc/hosts');
    expect(dd.className).not.toMatch(/font-mono/);
  });

  it('uses tighter spacing when dense=true', () => {
    const { container } = render(<KeyValuePair label="x" value="y" dense />);
    const row = container.firstChild as HTMLElement;
    expect(row.className).toMatch(/py-1\b/);
    // The non-dense default is py-1.5
    expect(row.className).not.toMatch(/py-1\.5/);
  });

  it('accepts a ReactNode value (not just text)', () => {
    render(
      <KeyValuePair
        label="Status"
        value={<span data-testid="custom-value">live</span>}
      />
    );
    expect(screen.getByTestId('custom-value')).toBeInTheDocument();
  });
});

describe('KeyValueList', () => {
  it('wraps children in a <dl>', () => {
    const { container } = render(
      <KeyValueList>
        <KeyValuePair label="A" value="1" />
        <KeyValuePair label="B" value="2" />
      </KeyValueList>
    );
    expect(container.querySelector('dl')).not.toBeNull();
  });

  it('forwards a custom className to the <dl>', () => {
    const { container } = render(
      <KeyValueList className="grid-cols-2">
        <KeyValuePair label="A" value="1" />
      </KeyValueList>
    );
    expect(container.querySelector('dl')!.className).toMatch(/grid-cols-2/);
  });
});
