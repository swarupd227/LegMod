import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Tabs, TabPanel, TabDescriptor } from './Tabs';

type TabId = 'one' | 'two' | 'three';

const TABS: TabDescriptor<TabId>[] = [
  { id: 'one', label: 'One' },
  { id: 'two', label: 'Two', count: 5, countTone: 'warn' },
  { id: 'three', label: 'Three' }
];

describe('Tabs', () => {
  it('renders tablist with the provided ariaLabel', () => {
    render(<Tabs<TabId> tabs={TABS} value="one" onChange={() => {}} ariaLabel="Tabs · demo" />);
    expect(screen.getByRole('tablist', { name: 'Tabs · demo' })).toBeInTheDocument();
  });

  it('marks only the active tab aria-selected and tabIndex=0', () => {
    render(<Tabs<TabId> tabs={TABS} value="two" onChange={() => {}} ariaLabel="t" />);
    const one = screen.getByRole('tab', { name: 'One' });
    const two = screen.getByRole('tab', { name: /Two/ });
    expect(one.getAttribute('aria-selected')).toBe('false');
    expect(two.getAttribute('aria-selected')).toBe('true');
    expect(one.getAttribute('tabindex')).toBe('-1');
    expect(two.getAttribute('tabindex')).toBe('0');
  });

  it('renders the count badge only when count > 0', () => {
    render(
      <Tabs<TabId>
        tabs={[
          { id: 'one', label: 'One', count: 0 },
          { id: 'two', label: 'Two', count: 5 }
        ]}
        value="one"
        onChange={() => {}}
        ariaLabel="t"
      />
    );
    expect(screen.queryByText('0')).toBeNull();
    expect(screen.getByText('5')).toBeInTheDocument();
  });

  it('calls onChange when a tab is clicked', async () => {
    const onChange = vi.fn();
    render(<Tabs<TabId> tabs={TABS} value="one" onChange={onChange} ariaLabel="t" />);
    await userEvent.click(screen.getByRole('tab', { name: 'Three' }));
    expect(onChange).toHaveBeenCalledWith('three');
  });

  it('moves selection right with ArrowRight', async () => {
    const onChange = vi.fn();
    render(<Tabs<TabId> tabs={TABS} value="one" onChange={onChange} ariaLabel="t" />);
    screen.getByRole('tab', { name: 'One' }).focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenCalledWith('two');
  });

  it('wraps to the first tab from the last with ArrowRight', async () => {
    const onChange = vi.fn();
    render(<Tabs<TabId> tabs={TABS} value="three" onChange={onChange} ariaLabel="t" />);
    screen.getByRole('tab', { name: 'Three' }).focus();
    await userEvent.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenCalledWith('one');
  });

  it('moves selection left with ArrowLeft and wraps from the first tab', async () => {
    const onChange = vi.fn();
    render(<Tabs<TabId> tabs={TABS} value="one" onChange={onChange} ariaLabel="t" />);
    screen.getByRole('tab', { name: 'One' }).focus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(onChange).toHaveBeenCalledWith('three');
  });

  it('jumps to first tab with Home and to last with End', async () => {
    const onChange = vi.fn();
    render(<Tabs<TabId> tabs={TABS} value="two" onChange={onChange} ariaLabel="t" />);
    screen.getByRole('tab', { name: /Two/ }).focus();
    await userEvent.keyboard('{Home}');
    expect(onChange).toHaveBeenLastCalledWith('one');
    await userEvent.keyboard('{End}');
    expect(onChange).toHaveBeenLastCalledWith('three');
  });

  it('ignores unrelated keys', async () => {
    const onChange = vi.fn();
    render(<Tabs<TabId> tabs={TABS} value="one" onChange={onChange} ariaLabel="t" />);
    screen.getByRole('tab', { name: 'One' }).focus();
    await userEvent.keyboard('a');
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('TabPanel', () => {
  it('hides the panel when active=false', () => {
    render(
      <TabPanel id="p1" tabId="t1" active={false}>
        <span>hidden text</span>
      </TabPanel>
    );
    expect(screen.getByText('hidden text').parentElement!.hasAttribute('hidden')).toBe(true);
  });

  it('shows the panel when active=true', () => {
    render(
      <TabPanel id="p1" tabId="t1" active={true}>
        <span>visible text</span>
      </TabPanel>
    );
    expect(screen.getByText('visible text').parentElement!.hasAttribute('hidden')).toBe(false);
  });

  it('wires aria-labelledby to the tabId', () => {
    render(
      <TabPanel id="p1" tabId="t1" active>
        <span>x</span>
      </TabPanel>
    );
    const panel = screen.getByRole('tabpanel');
    expect(panel.getAttribute('aria-labelledby')).toBe('t1');
    expect(panel.id).toBe('p1');
  });
});
