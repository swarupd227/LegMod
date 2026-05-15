import { test, expect } from './fixtures/api-mock';
import { PROJECT_ID_SOAP, PROJECT_DETAIL_SOAP } from './fixtures/sample-data';

test.describe('Project detail · SOAP track', () => {
  test('renders the project hero and the current stage chrome', async ({ page }) => {
    await page.goto(`/projects/${PROJECT_ID_SOAP}`);

    // Hero shows the project name as h1.
    await expect(page.getByRole('heading', { name: PROJECT_DETAIL_SOAP.name, level: 1 })).toBeVisible();

    // Current stage is B → Runtime Capture; the stage title is rendered in
    // the StageChromeHost. The string appears more than once (in the chrome
    // title and the stage chip aria-labels) so use first().
    await expect(page.getByText('Runtime Capture').first()).toBeVisible();
  });

  test('clicking a passed stage chip switches to its review screen', async ({ page }) => {
    await page.goto(`/projects/${PROJECT_ID_SOAP}`);

    // Stage A is "passed" in the fixture — it should be reviewable.
    // The stage chips are rendered as buttons by StageChromeHost.
    const stageA = page.getByRole('button', { name: /Stage A/ }).first();
    await stageA.click();

    // URL gets the ?stage=A override.
    await expect(page).toHaveURL(/\?stage=A$/);

    // Stage A is "Code Archaeology" for SOAP.
    await expect(page.getByText('Code Archaeology').first()).toBeVisible();
  });

  test('returning to the current stage clears the URL override', async ({ page }) => {
    await page.goto(`/projects/${PROJECT_ID_SOAP}?stage=A`);

    // The "reviewing" banner offers a return-to-current shortcut.
    const back = page.getByRole('button', { name: /return to current/i });
    if (await back.count()) {
      await back.click();
      await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_ID_SOAP}$`));
    } else {
      // Fallback: click the current-stage chip directly.
      await page.getByRole('button', { name: /Stage B/ }).first().click();
      await expect(page).toHaveURL(new RegExp(`/projects/${PROJECT_ID_SOAP}$`));
    }
  });

  test('breadcrumb reflects the project route', async ({ page }) => {
    await page.goto(`/projects/${PROJECT_ID_SOAP}`);

    const crumbs = page.getByLabel('Breadcrumb');
    await expect(crumbs).toContainText('Projects');
    await expect(crumbs).toContainText('Project detail');
  });
});
