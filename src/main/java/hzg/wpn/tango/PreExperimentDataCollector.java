/*
 * The main contributor to this project is Institute of Materials Research,
 * Helmholtz-Zentrum Geesthacht,
 * Germany.
 *
 * This project is a contribution of the Helmholtz Association Centres and
 * Technische Universitaet Muenchen to the ESS Design Update Phase.
 *
 * The project's funding reference is FKZ05E11CG1.
 *
 * Copyright (c) 2012. Institute of Materials Research,
 * Helmholtz-Zentrum Geesthacht,
 * Germany.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA
 */

package hzg.wpn.tango;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import fr.esrf.Tango.AttrWriteType;
import fr.esrf.Tango.DevFailed;
import fr.esrf.TangoApi.PipeBlobBuilder;
import hzg.wpn.predator.ApplicationContext;
import hzg.wpn.predator.meta.Meta;
import hzg.wpn.predator.web.ApplicationLoader;
import hzg.wpn.util.beanutils.BeanUtilsHelper;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.apache.commons.beanutils.DynaBean;
import org.apache.commons.beanutils.DynaProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tango.DeviceState;
import org.tango.server.ServerManager;
import org.tango.server.StateMachineBehavior;
import org.tango.server.annotation.*;
import org.tango.server.attribute.AttributeConfiguration;
import org.tango.server.attribute.AttributeValue;
import org.tango.server.attribute.IAttributeBehavior;
import org.tango.server.device.DeviceManager;
import org.tango.server.dynamic.DynamicManager;
import org.tango.server.pipe.PipeValue;
import org.tango.utils.DevFailedUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static hzg.wpn.predator.web.ApplicationLoader.initializeApplicationContext;

/**
 * Designed to be Thread condemned
 */
@Device(transactionType = TransactionType.NONE)
public class PreExperimentDataCollector {
    public static final String ERROR_MESSAGE = "data_set is null. load_data_set first.";
    public static final int TOMCAT_PORT = 10002;
    private static final Logger logger = LoggerFactory.getLogger(PreExperimentDataCollector.class);
    private static final Tomcat TOMCAT = new Tomcat();
    private static ApplicationContext APPLICATION_CONTEXT;
    private ApplicationContext appCtx;
    //@MonitoredSpecial(DataHandler.class)
    private volatile DynaBean data;
    @DeviceManagement
    private DeviceManager deviceManager;
    @State(isPolled = true, pollingPeriod = 3000)
    //@Monitored
    private volatile DeviceState state;
    @Status(isPolled = true, pollingPeriod = 3000)
    private String status;
    @DynamicManagement
    private DynamicManager dynamic;

    public synchronized static void setStaticContext(ApplicationContext applicationContext) {
        APPLICATION_CONTEXT = applicationContext;
    }

    public static void main(String... args) {
        ServerManager.getInstance().start(args, PreExperimentDataCollector.class);
    }

    public void setDeviceManager(DeviceManager deviceManager) {
        this.deviceManager = deviceManager;
    }

    @Pipe
    private PipeValue pipe;

    public PipeValue getPipe() {
        Preconditions.checkNotNull(data, ERROR_MESSAGE);
        logger.debug("Reading PreExperimentDataCollector' pipe...");

        PipeBlobBuilder pbb = new PipeBlobBuilder("any");//see DFS

        pbb.add("append", false);

        PipeBlobBuilder dataBlob = new PipeBlobBuilder("data");
        for (final DynaProperty dynaProperty : appCtx.getDataClass().getDynaProperties()) {
            Class<?> type = dynaProperty.getType();
            Object property = BeanUtilsHelper.getProperty(data, dynaProperty.getName(), type);
            if(property == null) continue;
            Object value;
            if(type.isArray()) {
                value = property;
            } else {
                value = Array.newInstance(type, 1);
                Array.set(value, 0, property);
            }

            dataBlob.add(dynaProperty.getName(),
                    new PipeBlobBuilder("predator:/" + dynaProperty.getName())
                            .add("value", value)
                            .build());
        }

        pbb.add("data", dataBlob.build());

        PipeValue result = new PipeValue();
        result.setValue(pbb.build(), System.currentTimeMillis());
        return result;
    }

    public DeviceState getState() {
        return state;
    }

    public void setState(DeviceState state) {
        this.state = state;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = String.format("%d: %s",System.currentTimeMillis(), status);
    }

    /**
     * Iterates over all users and loads their data sets
     *
     * @return
     * @throws Exception
     */
    @Attribute
    public String[] getDatasets() throws Exception {
        List<String> result = new ArrayList<>();

        //get all users
        Iterable<String> users = appCtx.getUsers();
        //add all data sets of each user
        for (String user : users) {
            Iterables.addAll(result, appCtx.getManager().getUserDataSetNames(user));
        }

        return result.toArray(new String[result.size()]);
    }

    @Command(inTypeDesc = "dataset_name")
    public void delete_data_set(final String name) throws Exception {
        Iterable<String> users = appCtx.getUsers();

        DynaBean data = getDataSet(name, users);

        appCtx.getManager().delete(data);

        this.data = null;
        deviceManager.pushStateChangeEvent(DeviceState.STANDBY);
        deviceManager.pushStatusChangeEvent(String.format("Dataset[%s] has been deleted", name));
    }

    private DynaBean getDataSet(final String name, Iterable<String> users) {
        DynaBean data = null;
        for (String user : users) {
            Optional<DynaBean> result = Iterables.tryFind(appCtx.getManager().getUserDataSets(user), new Predicate<DynaBean>() {
                @Override
                public boolean apply(@Nullable DynaBean input) {
                    return BeanUtilsHelper.getProperty(input, Meta.NAME, String.class).equals(name);
                }
            });
            if (result.isPresent()) {
                data = result.get();
                break;
            }
        }
        if (data == null)
            throw new NoSuchElementException("Dataset[" + name + "] can not be found!");

        return data;
    }

    @Command(inTypeDesc = "user_name;dataset_name")
    public void create_data_set(String[] args) throws Exception {
        if (args.length != 2)
            throw DevFailedUtils.newDevFailed("Exactly 2 arguments are expected here: user name and data set name.");
        String user = args[0];
        String name = args[1];
        data = appCtx.getManager().newDataSet(user, name);
        appCtx.getManager().save(data);
        deviceManager.pushStateChangeEvent(DeviceState.ON);
        deviceManager.pushStatusChangeEvent(String.format("Dataset[%s] for user[%s] has been created", name, user));
    }

    @Command(inTypeDesc = "dataset_name")
    public void load_data_set(final String name) throws Exception {
        //get all users
        Iterable<String> users = appCtx.getUsers();
        //add all data sets of each user
        this.data = getDataSet(name, users);
        deviceManager.pushStateChangeEvent(DeviceState.ON);
        deviceManager.pushStatusChangeEvent(String.format("Dataset[%s] has been loaded", name));
    }


    public void setDynamic(DynamicManager dynamic) {
        this.dynamic = dynamic;
    }

    @Init
    public void init() {
        new TomcatStarterTask().run();

        appCtx = initializeApplicationContext();
    }

    @Delete
    public void delete() throws Exception {
        TOMCAT.stop();
        deviceManager.pushStateChangeEvent(DeviceState.OFF);
    }

    private IAttributeBehavior createNewAttribute(final DynaProperty dynaProperty, final ApplicationContext appCtx) {
        final StateMachineBehavior stateMachine = new StateMachineBehavior();
        stateMachine.setDeniedStates(DeviceState.FAULT, DeviceState.STANDBY);
        return new IAttributeBehavior() {
            @Override
            public AttributeConfiguration getConfiguration() throws DevFailed {
                AttributeConfiguration configuration = new AttributeConfiguration();
                configuration.setName(dynaProperty.getName());
                configuration.setType(dynaProperty.getType());
                configuration.getAttributeProperties().setLabel(dynaProperty.getName());
                configuration.setWritable(AttrWriteType.READ_WRITE);
                return configuration;
            }

            @Override
            public AttributeValue getValue() throws DevFailed {
                if (data == null) throw DevFailedUtils.newDevFailed("data_set is null. load_data_set first.");
                return new AttributeValue(BeanUtilsHelper.getProperty(data, getConfiguration().getName(), getConfiguration().getType()));
            }

            @Override
            public void setValue(AttributeValue value) throws DevFailed {
                data.set(getConfiguration().getName(), value.getValue());
                try {
                    appCtx.getManager().save(data);
                } catch (IOException e) {
                    throw DevFailedUtils.newDevFailed(e.getClass().getSimpleName(), e.getLocalizedMessage());
                }
            }

            @Override
            public StateMachineBehavior getStateMachine() throws DevFailed {
                return stateMachine;
            }
        };
    }

    public void setAppCtx(ApplicationContext appCtx) {
        this.appCtx = appCtx;
    }

    public class TomcatStarterTask implements Runnable {
        @Override
        public void run() {
            TOMCAT.setPort(TOMCAT_PORT);

            TOMCAT.setConnector(TOMCAT.getConnector());


            ApplicationLoader.initializeWebapp(TOMCAT);

            ApplicationLoader.initializeLoginProperties(TOMCAT);

            try {
                TOMCAT.start();
                if (TOMCAT.getConnector().getState() == LifecycleState.FAILED)
                    throw new LifecycleException("Failed to initialize default connector!");

                appCtx = APPLICATION_CONTEXT;
                //TODO set status
                //populate attributes
                for (final DynaProperty dynaProperty : appCtx.getDataClass().getDynaProperties()) {
                    dynamic.addAttribute(createNewAttribute(dynaProperty, appCtx));
                }

                deviceManager.pushStateChangeEvent(DeviceState.STANDBY);
                deviceManager.pushStatusChangeEvent("Tomcat started");
            } catch (DevFailed devFailed) {
                DevFailedUtils.logDevFailed(devFailed, logger);
                deviceManager.pushStateChangeEvent(DeviceState.FAULT);
                deviceManager.pushStatusChangeEvent("Failed to start Tomcat");
            } catch (Exception e) {
                logger.error("Failed to start Tomcat: {}", e.getMessage());
                deviceManager.pushStateChangeEvent(DeviceState.FAULT);
                deviceManager.pushStatusChangeEvent("Failed to start Tomcat");
            }
        }
    }
}