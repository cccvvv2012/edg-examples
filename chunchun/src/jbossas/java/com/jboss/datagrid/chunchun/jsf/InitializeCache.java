/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.jboss.datagrid.chunchun.jsf;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.faces.application.Application;
import javax.faces.event.AbortProcessingException;
import javax.faces.event.SystemEvent;
import javax.faces.event.SystemEventListener;
import javax.imageio.ImageIO;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.UserTransaction;
import org.infinispan.api.BasicCache;
import com.jboss.datagrid.chunchun.model.Post;
import com.jboss.datagrid.chunchun.model.PostKey;
import com.jboss.datagrid.chunchun.model.User;
import com.jboss.datagrid.chunchun.session.CacheContainerProvider;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

/**
 * A JSF listener used to populate the cache with users, posts and user watchers/watching
 * people.
 *
 * @author Martin Gencur
 *
 */
public class InitializeCache implements SystemEventListener {

   private static final int       USER_COUNT                 = Integer.getInteger("chunchun.cache.init.users", 3000);
   private static final int       SEVEN_DAYS_IN_MILLISECONDS = 7 * 24 * 3600 * 1000;
   private static final int       USER_WATCHES_COUNT         = Integer.getInteger("chunchun.cache.init.watches",20);
   private static final int       POSTS                      = Integer.getInteger("chunchun.cache.init.posts",20);
   private Random                 randomNumber               = new Random();
   private Logger                 log                        = Logger.getLogger(this.getClass().getName());
   private CacheContainerProvider provider;
   private UserTransaction        utx;

   @Override
   public void processEvent(SystemEvent event) throws AbortProcessingException {
      provider = getContextualInstance(getBeanManagerFromJNDI(), CacheContainerProvider.class);
      startup();
   }

   public void startup() {
      if (Boolean.getBoolean("chunchun.cache.init.skip")) return;

      BasicCache<String, Object> users = provider.getCacheContainer().getCache("userCache");
      BasicCache<PostKey, Object> posts = provider.getCacheContainer().getCache("postCache");

      utx = getUserTransactionFromJNDI();

      try {
         utx.begin();
         for (int i = 1; i <= USER_COUNT; i++) {
            User u = null;
            if (i % 2 == 1) {
               u = new User("user" + i, "Name" + i, "Surname" + i, "tmpPasswd",
                        "Description of person " + i, loadImageFromFile("images" + File.separator + "user1.jpg"));
            } else {
               u = new User("user" + i, "Name" + i, "Surname" + i, "tmpPasswd",
                        "Description of person " + i, loadImageFromFile("images" + File.separator + "nophoto.jpg"));
            }

            String encryptedPass = hashPassword("pass" + i);
            u.setPassword(encryptedPass);

            // GENERATE POSTS FOR EACH USER
            TreeSet<Long> randomTimesSorted = new TreeSet<Long>();
            for (int j = 1; j != POSTS; j++) {
                randomTimesSorted.add(getRandomTime());
            }

            for (int j = 1; j != POSTS; j++) {
               long randomTime = randomTimesSorted.pollFirst();
               Post t = new Post(u.getUsername(), "Post number " + j + " for user "
                        + u.getName() + " at " + new Date(randomTime), randomTime);
               // store the post in a cache
               posts.put(t.getKey(), t);
               u.getPosts().add(t.getKey());
            }
            // store the user in a cache
            users.put(u.getUsername(), u);
         }

         // GENERATE RANDOM WATCHERS AND WATCHING FOR EACH USER
         for (int i = 1; i != USER_COUNT; i++) {
            User u = (User) users.get("user" + i);
            for (User watching : generateRandomUsers(u, USER_WATCHES_COUNT, USER_COUNT)) {
               if (!u.getUsername().equals(watching.getUsername())) {
                  u.getWatching().add(watching.getUsername());
               }
            }
            users.replace("user" + i, u);
         }

         for (int i = 1; i != USER_COUNT; i++) {
            User u = (User) users.get("user" + i);
            for (String username: u.getWatching()) {
               User us = (User) users.get(username);
               if (!us.getWatchers().contains(u.getUsername())) {
                  us.getWatchers().add(u.getUsername());
                  users.replace(us.getUsername(), us);
               }
            }
         }
         utx.commit();
         log.info("Successfully imported data!");
      } catch (Exception e) {
         log.log(Level.SEVERE, "An exception occured while populating the datagrid! Rolling back the transaction",e);
         if (utx != null) {
            try {
               utx.rollback();
            } catch (Exception e1) {
            	log.log(Level.SEVERE, "failed to rollback transaction transaction for cache init", e1);
            }
         }
      }
   }
   
   private BufferedImage loadImageFromFile(String fileName) {
      BufferedImage image = null;
      try {
         image = ImageIO.read(this.getClass().getClassLoader().getResourceAsStream(fileName));
         return image;
      } catch (IOException e) {
         throw new RuntimeException("Unable to load image from file " + fileName);
      }
   }

   private long getRandomTime() {
      // get random time at most 7 days old
      return Calendar.getInstance().getTimeInMillis()
               - randomNumber.nextInt(SEVEN_DAYS_IN_MILLISECONDS);
   }

   private Set<User> generateRandomUsers(User forWhom, int count, int outOf) {
      BasicCache<String, Object> users = provider.getCacheContainer().getCache("userCache");
      Random r = new Random(getRandomTime());
      Set<User> result = new HashSet<User>();
      while (result.size() != count) {
         int id = (r.nextInt(outOf - 1)) + 1; // do not return 0
         User u = (User) users.get("user" + id);
         if (u != null && u.equals(forWhom))
            continue;
         result.add(u);
      }
      return result;
   }

   public static String hashPassword(String password) {
      String hashword = null;
      try {
         MessageDigest md5 = MessageDigest.getInstance("MD5");
         md5.update(password.getBytes());
         BigInteger hash = new BigInteger(1, md5.digest());
         hashword = hash.toString(16);
      } catch (NoSuchAlgorithmException nsae) {
         throw new RuntimeException("No MD5 algorithm found for password encryption!");
      }
      return hashword;
   }

   public static int getUserCount() {
      return USER_COUNT;
   }
   
   private BeanManager getBeanManagerFromJNDI() {
      InitialContext context;
      Object result;
      try {
         context = new InitialContext();
         result = context.lookup("java:comp/BeanManager");
      } catch (NamingException e) {
         throw new RuntimeException("BeanManager could not be found in JNDI", e);
      }
      return (BeanManager) result;
   }

   private UserTransaction getUserTransactionFromJNDI() {
      InitialContext context;
      Object result;
      try {
         context = new InitialContext();
         result = context.lookup("java:comp/UserTransaction");
      } catch (NamingException ex) {
         throw new RuntimeException("UserTransaction could not be found in JNDI", ex);
      }
      return (UserTransaction) result;
   }

   @SuppressWarnings("unchecked")
   public <T> T getContextualInstance(final BeanManager manager, final Class<T> type) {
      T result = null;
      Bean<T> bean = (Bean<T>) manager.resolve(manager.getBeans(type));
      if (bean != null) {
         CreationalContext<T> context = manager.createCreationalContext(bean);
         if (context != null) {
            result = (T) manager.getReference(bean, type, context);
         }
      }
      return result;
   }

   @Override
   public boolean isListenerForSource(Object source) {
      return source instanceof Application;
   }
}
