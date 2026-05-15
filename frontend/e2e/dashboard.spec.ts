import { test, expect } from './fixtures/api-mock';
import { PROJECTS } from './fixtures/sample-data';

test.describe('Workspace dashboard', () => {
  test('renders the empty state when no projects exist', async ({ page, mockApi }) => {
    await mockApi.json('**/api/v1/workspaces/*/projects', []);

    await page.goto('/');

    // The H1 is always present — visual anchor.
    await expect(page.getByRole('heading', { name: 'Projects', level: 1 })).toBeVisible();

    // Empty state copy from EmptyProjectState.
    await expect(page.getByText('Start your first migration')).toBeVisible();

    // Empty state primary CTA opens the modal — there are two "Create project"
    // buttons in this state (the always-on "New project" CTA and the empty-state
    // CTA). Both should be visible.
    await expect(page.getByRole('button', { name: 'New project' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create project' })).toBeVisible();
  });

  test('renders the project grid with metric tiles when projects exist', async ({ page }) => {
    await page.goto('/');

    // Both seeded projects render as cards.
    await expect(page.getByRole('heading', { name: PROJECTS[0].name, level: 3 })).toBeVisible();
    await expect(page.getByRole('heading', { name: PROJECTS[1].name, level: 3 })).toBeVisible();

    // Stat tiles in the page header.
    await expect(page.getByText('Active projects')).toBeVisible();
    await expect(page.getByText('Stages in flight')).toBeVisible();
    await expect(page.getByText('Gates passed')).toBeVisible();
  });

  test('shows mode and risk badges on each card', async ({ page }) => {
    await page.goto('/');

    // ModeBadge text — emitted by components/Badges.tsx.
    await expect(page.getByText('SOAP', { exact: false }).first()).toBeVisible();
    await expect(page.getByText('UPLIFT', { exact: false }).first()).toBeVisible();
  });

  test('clicking a project card navigates to its detail page', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('heading', { name: PROJECTS[0].name, level: 3 }).click();

    await expect(page).toHaveURL(new RegExp(`/projects/${PROJECTS[0].id}$`));
  });

  test('shows the gateway-unreachable error when the API fails', async ({ page, mockApi }) => {
    await mockApi.json('**/api/v1/workspaces/*/projects', { error: 'boom' }, 500);

    await page.goto('/');

    await expect(page.getByText('Workspace service unreachable')).toBeVisible();
    await expect(page.getByRole('button', { name: /retry/i })).toBeVisible();
  });
});
