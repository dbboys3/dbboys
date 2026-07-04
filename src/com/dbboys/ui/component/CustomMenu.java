package com.dbboys.ui.component;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseEvent;

/**
 * A Menu that auto-closes all open submenus when the mouse moves to a
 * different menu or menu-item within the same parent popup.
 *
 * <p>Replace every {@code <Menu>} that contains child items in FXML with
 * {@code <CustomMenu>}, and use it programmatically in context menus.
 * No additional controller wiring is needed — the hover-close filter is
 * installed automatically the first time a CustomMenu (or any Menu whose
 * submenu uses one) opens.</p>
 */
public class CustomMenu extends Menu {

    private static final String FILTER_KEY = "CustomMenu.hoverCloseFilter";

    public CustomMenu() {
        super();
    }

    public CustomMenu(String text) {
        super(text);
    }

    public CustomMenu(String text, Node graphic) {
        super(text, graphic);
    }

    public CustomMenu(String text, Node graphic, MenuItem... items) {
        super(text, graphic, items);
    }

    // ---- Automatic hover-close filter -----------------------------------

    /**
     * Call this once from the owning popup's onShowing handler when none of
     * the submenus are CustomMenu instances but you still want the
     * hover-close behavior for plain {@link Menu} submenus.
     *
     * <p>For CustomMenu submenus the filter is installed automatically;
     * this is a convenience escape hatch.</p>
     */
    public static void installOn(Menu anyMenuWithSubmenus) {
        ContextMenu popup = anyMenuWithSubmenus.getParentPopup();
        if (popup == null) {
            return;
        }
        ensureFilter(popup);
    }

    // ---- internals ------------------------------------------------------

    @Override
    public void show() {
        super.show();
        ContextMenu popup = getParentPopup();
        if (popup != null) {
            ensureFilter(popup);
        }
    }

    private static void ensureFilter(ContextMenu popup) {
        if (Boolean.TRUE.equals(popup.getProperties().get(FILTER_KEY))) {
            return;
        }
        popup.getProperties().put(FILTER_KEY, Boolean.TRUE);

        popup.skinProperty().addListener((obs, oldS, newS) -> {
            if (newS != null) {
                attachFilter(newS.getNode(), popup);
            }
        });
        if (popup.getSkin() != null) {
            attachFilter(popup.getSkin().getNode(), popup);
        }
    }

    private static void attachFilter(Node skinRoot, ContextMenu popup) {
        if (Boolean.TRUE.equals(skinRoot.getProperties().get("CustomMenu.filterNode"))) {
            return;
        }
        skinRoot.getProperties().put("CustomMenu.filterNode", Boolean.TRUE);

        skinRoot.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, event -> {
            if (!(event.getTarget() instanceof Node target)) {
                return;
            }
            Node enteredItem = findMenuItemAncestor(target, skinRoot);
            if (enteredItem == null) {
                return;
            }
            // Hide every currently-showing submenu whose label does not
            // match the menu-item that was just entered.
            for (MenuItem item : popup.getItems()) {
                if (item instanceof Menu m && m.isShowing()
                        && !labelMatchesNode(m.getText(), enteredItem)) {
                    m.hide();
                }
            }
        });
    }

    private static Node findMenuItemAncestor(Node target, Node skinRoot) {
        Node cur = target;
        while (cur != null && cur != skinRoot) {
            if (cur.getStyleClass().contains("menu-item")) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean labelMatchesNode(String text, Node node) {
        if (text == null) {
            return false;
        }
        return containsLabelText(node, text);
    }

    private static boolean containsLabelText(Node node, String text) {
        if (node == null || text == null) {
            return false;
        }
        if (node instanceof Labeled lb && text.equals(lb.getText())) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsLabelText(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }
}
