import { expect, test, type Page } from "@playwright/test";

async function signIn(page: Page) {
  await page.goto("/login");
  await page.getByLabel("Username").fill(process.env.E2E_ADMIN_USER ?? "admin");
  await page.getByLabel("Password").fill(process.env.E2E_ADMIN_PASSWORD ?? "admin");
  await page.getByRole("button", { name: /sign in/i }).click();
  await expect(page).toHaveURL(/\/rules$/);
}

test("admin can log in and navigate the operational console", async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole("heading", { name: "Rules" })).toBeVisible();

  // Rule testing is available from the sidebar; the Rules page no longer has
  // a duplicate action button in its header.
  await page.getByRole("link", { name: "Run Test", exact: true }).click();
  await expect(page).toHaveURL(/\/rules\/test$/);

  await page.goto("/deliveries");
  await expect(page.getByRole("heading", { name: /deliver/i })).toBeVisible();
});

test("admin can create, edit, and archive a rule", async ({ page }) => {
  await signIn(page);

  const originalName = "E2E CRUD Rule";
  const updatedName = "E2E CRUD Rule Updated";
  await page.getByRole("link", { name: "New Rule" }).click();
  await expect(page).toHaveURL(/\/rules\/new$/);
  await page.getByLabel("Name").fill(originalName);
  await page.getByLabel("Event Type").fill("transaction");
  await page.getByLabel("Source").fill("e2e");
  await page.getByRole("button", { name: "Add condition" }).click();
  await page.getByLabel("Field").fill("data.amount");
  await page.getByLabel("Value").fill("100");
  await page.getByRole("button", { name: "Save Rule" }).click();

  await expect(page).toHaveURL(/\/rules$/);
  await expect(page.getByText(originalName, { exact: true })).toBeVisible();
  const createdRow = page.getByRole("row").filter({ hasText: originalName });
  await createdRow.getByLabel("Edit").click();

  await expect(page).toHaveURL(/\/rules\/\d+\/edit$/);
  await page.getByLabel("Name").fill(updatedName);
  await page.getByRole("button", { name: "Save Rule" }).click();
  await expect(page).toHaveURL(/\/rules$/);
  await expect(page.getByText(updatedName, { exact: true })).toBeVisible();

  page.once("dialog", (dialog) => dialog.accept());
  const updatedRow = page.getByRole("row").filter({ hasText: updatedName });
  await updatedRow.getByLabel("Archive").click();
  await expect(page.getByText(updatedName, { exact: true })).toHaveCount(0);
});
