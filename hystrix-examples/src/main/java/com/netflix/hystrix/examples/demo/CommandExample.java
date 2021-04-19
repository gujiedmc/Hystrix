package com.netflix.hystrix.examples.demo;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import rx.Observable;

import java.util.concurrent.ExecutionException;

/**
 * @author gujiedmc
 * @date 2021-04-04
 */
public class CommandExample {

    public static void main(String[] args) throws ExecutionException, InterruptedException {

        // 同步
        HystrixCommand<UserEntity> getUserInfoCommand = new GetUserInfoCommand(1L);
        UserEntity userEntity = getUserInfoCommand.execute();
        System.out.println(userEntity);
        System.out.println("=================================================================");
//
//        // 异步
//        HystrixCommand<UserEntity> getUserInfoCommandAsync = new GetUserInfoCommand(1L);
//        Future<UserEntity> future = getUserInfoCommandAsync.queue();
//        System.out.println(future.get());
//        System.out.println("=================================================================");

        // 转为响应式
//        HystrixCommand<UserEntity> getUserInfoCommandObserve = new GetUserInfoCommand(1L);
//        Observable<UserEntity> observe = getUserInfoCommandObserve.observe();
//        observe.subscribe(System.out::println);
//        System.out.println("=================================================================");

//        HystrixCommand<UserEntity> getUserInfoCommandObserve2 = new GetUserInfoCommand(1L);
//        Observable<UserEntity> userEntityObservable = getUserInfoCommandObserve2.toObservable();
//        userEntityObservable.subscribe(System.out::println);

    }

    private static class GetUserInfoCommand extends HystrixCommand<UserEntity> {

        private Long userId;

        protected GetUserInfoCommand(Long userId) {
            super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("user")));
            this.userId = userId;
        }

        @Override
        protected UserEntity run() throws Exception {
            System.out.println("execute http query");
            UserEntity userEntity = new UserEntity();
            userEntity.setId(userId);
            return userEntity;
        }
    }


    private static class UserEntity {
        private Long id;
        private String username;
        private String password;
        private Integer age;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }
}
