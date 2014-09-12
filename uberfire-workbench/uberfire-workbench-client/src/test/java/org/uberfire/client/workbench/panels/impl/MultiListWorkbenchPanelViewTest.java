package org.uberfire.client.workbench.panels.impl;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.uberfire.client.workbench.widgets.listbar.ListBarWidget;
import org.uberfire.mvp.Command;

import com.google.gwt.event.logical.shared.SelectionHandler;
import com.google.gwt.user.client.ui.RequiresResize;
import com.google.gwtmockito.GwtMockitoTestRunner;

@RunWith(GwtMockitoTestRunner.class)
public class MultiListWorkbenchPanelViewTest extends AbstractDockingWorkbenchPanelViewTest {

    @Mock ListBarWidget listBar;
    @Mock MultiListWorkbenchPanelPresenter presenter;

    @InjectMocks MultiListWorkbenchPanelView view;

    @Override
    protected AbstractDockingWorkbenchPanelView<?> getViewToTest() {
        return view;
    }

    @Test
    public void setupWidget() {
        view.setupWidget();

        verify( listBar ).addSelectionHandler( any(SelectionHandler.class) );
        verify( listBar ).addOnFocusHandler( any(Command.class) );
    }

    @Test
    public void shouldPropagateOnResize() {
        view.onResize();
        RequiresResize viewChild = (RequiresResize) view.getWidget();
        verify( viewChild, times( 1 ) ).onResize();
    }
}
