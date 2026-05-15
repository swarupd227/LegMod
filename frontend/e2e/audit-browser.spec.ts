import { test, expect } from './fixtures/api-mock';
import { PROJECTS } from './fixtures/sample-data';

test.describe('Audit browser', () => {
  test('renders the page header and auto-selects the first project', async ({ page }) => {
    await page.goto('/audit');

    await expect(page.getByRole('heading', { name: 'Provenance Browser', level: 1 })).toBeVisible();

    // The page eyebrow.
    await expect(page.getByText('Audit trail').first()).toBeVisible();

    // The picker is a <select>; its selected value reflects the auto-pick.
    const picker = page.getByLabel('Select a project to inspect provenance for');
    await expect(picker).toHaveValue(PROJECTS[0].id);
  });

  test('exposes the CSV export link with the correct query', async ({ page }) => {
    await page.goto('/audit');

    const exportLink = page.getByRole('link', { name: /export csv/i });
    await expect(exportLink).toBeVisible();
    const href = await exportLink.getAttribute('href');
    expect(href).toContain(`/api/v1/projects/${PROJECTS[0].id}/provenance/export.csv`);
  });

  test('renders provenance entries returned from the gateway', async ({ page }) => {
    await page.goto('/audit');

    // Both fixture entries should land somewhere in the trail. Action labels
    // are humanized (snake_case → Sentence case) by the AuditBrowser component.
    await expect(page.getByText(/Archaeology run/i).first()).toBeVisible();
    await expect(page.getByText(/Archaeology accepted/i).first()).toBeVisible();
  });

  test('navigates back to the workspace via the side nav', async ({ page }) => {
    await page.goto('/audit');

    // The primary nav link "Workspaces" should return to /.
    await page.getByRole('link', { name: 'Workspaces' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { name: 'Projects', level: 1 })).toBeVisible();
  });
});
