import { test, expect } from './fixtures/api-mock';
import { WORKSPACE_ID } from './fixtures/sample-data';

test.describe('Create project flow', () => {
  test('opens the modal, validates required fields, and posts the form', async ({ page }) => {
    let postedBody: Record<string, unknown> | null = null;
    await page.route('**/api/v1/projects', async (route) => {
      if (route.request().method() === 'POST') {
        postedBody = JSON.parse(route.request().postData() ?? '{}');
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: '99999999-9999-9999-9999-999999999999',
            name: postedBody?.name,
            mode: postedBody?.mode,
            riskTier: 'MEDIUM',
            currentStage: 'A'
          })
        });
        return;
      }
      await route.continue();
    });

    await page.goto('/');

    // Open the modal via the always-on "New project" toolbar button.
    await page.getByRole('button', { name: 'New project' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('heading', { name: 'Create migration project' })).toBeVisible();

    // The submit button starts disabled because the form is invalid.
    const submit = dialog.getByRole('button', { name: 'Create project' });
    await expect(submit).toBeDisabled();

    // Fill the form.
    await dialog.getByPlaceholder(/Order Management uplift/i).fill('Pilot SOAP migration');
    await dialog.getByPlaceholder('e.g. Apache Axis 1.3').fill('Apache Axis 1.4');
    await dialog.getByPlaceholder('e.g. JAX-WS RI 4.0').fill('JAX-WS RI 4.0');

    // SOAP is the default — locate by text inside the migration-track group.
    // The buttons here are <button role="radio"> which Playwright's accessibility
    // tree exposes neither as "button" nor "radio" reliably across versions, so
    // we fall back to a CSS selector against the role=radio attribute.
    const trackGroup = dialog.getByRole('radiogroup', { name: 'Migration track' });
    const soapTrack = trackGroup.locator('[role="radio"]').filter({ hasText: 'SOAP migration' });
    await expect(soapTrack).toHaveAttribute('aria-checked', 'true');

    await expect(submit).toBeEnabled();
    await submit.click();

    // POSTed body has the expected shape.
    await expect.poll(() => postedBody?.name).toBe('Pilot SOAP migration');
    await expect.poll(() => postedBody?.mode).toBe('SOAP');
    await expect.poll(() => postedBody?.workspaceId).toBe(WORKSPACE_ID);
    await expect.poll(() => postedBody?.sourceFramework).toBe('Apache Axis 1.4');

    // Modal closes after successful create.
    await expect(dialog).not.toBeVisible();
  });

  test('keeps the submit button disabled until the name is at least 3 chars', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'New project' }).click();

    const dialog = page.getByRole('dialog');
    const submit = dialog.getByRole('button', { name: 'Create project' });
    const name = dialog.getByPlaceholder(/Order Management uplift/i);

    await expect(submit).toBeDisabled();

    await name.fill('AB');
    await expect(submit).toBeDisabled();

    await name.fill('ABC');
    await expect(submit).toBeEnabled();
  });

  test('migration track radios toggle and update aria-checked', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'New project' }).click();
    const dialog = page.getByRole('dialog');
    const trackGroup = dialog.getByRole('radiogroup', { name: 'Migration track' });
    const soap   = trackGroup.locator('[role="radio"]').filter({ hasText: 'SOAP migration' });
    const uplift = trackGroup.locator('[role="radio"]').filter({ hasText: 'Framework uplift' });

    // Default is SOAP.
    await expect(soap).toHaveAttribute('aria-checked', 'true');
    await expect(uplift).toHaveAttribute('aria-checked', 'false');

    // Click UPLIFT — only that one is now checked.
    await uplift.click();
    await expect(uplift).toHaveAttribute('aria-checked', 'true');
    await expect(soap).toHaveAttribute('aria-checked', 'false');
  });

  test('cancel button closes the modal without sending a request', async ({ page }) => {
    let postCount = 0;
    await page.route('**/api/v1/projects', (route) => {
      if (route.request().method() === 'POST') postCount += 1;
      return route.continue();
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'New project' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).not.toBeVisible();
    expect(postCount).toBe(0);
  });
});
