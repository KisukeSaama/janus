import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { I18nProvider } from '../i18n';
import { ConfirmAction, RowMenu, type RowAction } from './Actions';

function Wrapper({ children }: { children: ReactNode }) {
  return <I18nProvider>{children}</I18nProvider>;
}

const openMenu = async () => {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: /pok(é|e)api/i }));
  return user;
};

function renderMenu(actions: RowAction[]) {
  return render(<RowMenu label="PokéAPI" actions={actions} />, { wrapper: Wrapper });
}

describe('RowMenu', () => {
  it('draws nothing when the reader may do nothing else to the row', () => {
    const { container } = renderMenu([]);

    expect(container).toBeEmptyDOMElement();
  });

  it('names the authority each entry acts under', async () => {
    const onConfirm = vi.fn(async () => {});
    renderMenu([
      { key: 'mine', label: 'Turn off for my account', group: 'Your account', consequence: 'Yours stops.', onConfirm },
      { key: 'all', label: 'Remove from the catalogue', group: 'Whole deployment', consequence: 'Everyone loses it.', destructive: true, onConfirm },
    ]);
    await openMenu();

    expect(screen.getByRole('group', { name: 'Your account' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Whole deployment' })).toBeInTheDocument();
    expect(screen.getAllByRole('menuitem')).toHaveLength(2);
  });

  it('hands a consequential entry to the dialog instead of running it', async () => {
    const onConfirm = vi.fn(async () => {});
    renderMenu([
      {
        key: 'all',
        label: 'Remove from the catalogue',
        destructive: true,
        consequence: 'This removes the API for every account.',
        confirm: 'Confirm delete',
        onConfirm,
      },
    ]);
    const user = await openMenu();

    await user.click(screen.getByRole('menuitem', { name: 'Remove from the catalogue' }));

    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    const dialog = screen.getByRole('alertdialog', { name: 'Remove from the catalogue' });
    expect(dialog).toHaveAccessibleDescription('This removes the API for every account.');

    await user.click(screen.getByRole('button', { name: 'Confirm delete' }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
  });

  it('gives the row back its own control once the dialog is answered', async () => {
    renderMenu([{ key: 'all', label: 'Remove', destructive: true, consequence: 'Gone.', onConfirm: vi.fn(async () => {}) }]);
    const user = await openMenu();
    await user.click(screen.getByRole('menuitem', { name: 'Remove' }));

    await user.click(screen.getByRole('button', { name: /cancel|annuler/i }));

    expect(screen.getByRole('button', { name: /pok(é|e)api/i })).toHaveFocus();
  });

  it('runs a plain entry immediately and closes behind it', async () => {
    const onSelect = vi.fn();
    renderMenu([{ key: 'edit', label: 'Edit API', onSelect }]);
    const user = await openMenu();

    await user.click(screen.getByRole('menuitem', { name: 'Edit API' }));

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('gives the row back its trigger on Escape', async () => {
    renderMenu([{ key: 'edit', label: 'Edit API', onSelect: vi.fn() }]);
    const user = await openMenu();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /pok(é|e)api/i })).toHaveFocus();
  });
});

describe('ConfirmAction', () => {
  const destructive = {
    trigger: 'Delete',
    title: 'Delete pokeapi',
    confirm: 'Confirm delete',
    pending: 'Deleting',
    description: 'Its subscriptions stop and the stored value is destroyed.',
    destructive: true,
  };

  it('writes nothing until the dialog is answered', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn(async () => {});
    render(<ConfirmAction {...destructive} onConfirm={onConfirm} />, { wrapper: Wrapper });

    await user.click(screen.getByRole('button', { name: 'Delete pokeapi' }));

    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('alertdialog', { name: 'Delete pokeapi' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Confirm delete' }));

    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
  });

  // The key that dismisses a destructive dialog and the key that fires it are never the same one.
  it('opens a destructive question on the way out, not on the deed', async () => {
    const user = userEvent.setup();
    render(<ConfirmAction {...destructive} onConfirm={vi.fn(async () => {})} />, { wrapper: Wrapper });

    await user.click(screen.getByRole('button', { name: 'Delete pokeapi' }));

    expect(screen.getByRole('button', { name: /cancel|annuler/i })).toHaveFocus();
  });

  it('holds the dialog open while the write is in flight', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    const onConfirm = vi.fn(() => new Promise<void>((resolve) => (release = resolve)));
    render(<ConfirmAction {...destructive} onConfirm={onConfirm} />, { wrapper: Wrapper });
    await user.click(screen.getByRole('button', { name: 'Delete pokeapi' }));
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }));

    expect(await screen.findByRole('button', { name: 'Deleting' })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('alertdialog')).toBeInTheDocument();

    release();

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
  });
});
