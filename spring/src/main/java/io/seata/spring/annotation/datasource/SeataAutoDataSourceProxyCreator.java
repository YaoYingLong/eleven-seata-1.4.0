/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.spring.annotation.datasource;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.aop.support.DefaultIntroductionAdvisor;
import org.springframework.beans.BeansException;

/**
 * @author xingfudeshi@gmail.com
 */
public class SeataAutoDataSourceProxyCreator extends AbstractAutoProxyCreator { // 给DataSource自动创建代理
    private static final Logger LOGGER = LoggerFactory.getLogger(SeataAutoDataSourceProxyCreator.class);
    private final List<String> excludes;
    private final Advisor advisor;

    public SeataAutoDataSourceProxyCreator(boolean useJdkProxy, String[] excludes, String dataSourceProxyMode) {
        this.excludes = Arrays.asList(excludes);
        this.advisor = new DefaultIntroductionAdvisor(new SeataAutoDataSourceProxyAdvice(dataSourceProxyMode));
        setProxyTargetClass(!useJdkProxy);
    }

    @Override
    protected Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName, TargetSource customTargetSource) throws BeansException {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Auto proxy of [{}]", beanName);
        }
        return new Object[]{advisor};
    }

    @Override
    protected boolean shouldSkip(Class<?> beanClass, String beanName) {
        // beanClass是DataSource的子类 && beanClass不是beanClass的子类 && beanClass没有被排除，则创建通过SeataAutoDataSourceProxyAdvice创建代理
        return !DataSource.class.isAssignableFrom(beanClass) || SeataProxy.class.isAssignableFrom(beanClass) || excludes.contains(beanClass.getName());
    }
}
