package com.frank.springboot.web;

import com.alibaba.dubbo.config.annotation.Reference;
import com.frank.springboot.model.Student;
import com.frank.springboot.service.StudentService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

/**
 * 2022-01-17 16:41:15
 * 之前的043demo项目consumer启动报错没找到原因
 * 重建项目
 */
@Controller
@Slf4j
public class StudentController {

    @Reference(interfaceClass = StudentService.class,version = "1.0.0",check = false)
    private StudentService studentService;

    @RequestMapping(value = "/student/detail/{id}")
    public String studentDetail(Model model, @PathVariable("id") Integer id){
        //根据学生id查询详情
        log.debug("服务消费者，根据学生id查询详情，日志系统debug测试。");
        log.error("服务消费者，根据学生id查询详情，日志系统error测试。");
        Student student = studentService.queryStudentById(id);
        model.addAttribute("student",student);

        studentService.putRedis(id,model);
        log.warn("值已存入redis。");
        return "studentDetail";
    }

    //rabbitmq实验：先访问此接口，消息写入mq
    //从redis缓存取数据，如果没有再从mysql取，然后存入redis
    @RequestMapping(value = "/student/redis/{id}")
    public String redisStudent(Model model, @PathVariable("id") Integer id){
        Student student = studentService.queryStudentByRedis(id);
        model.addAttribute("student",student);
        log.info("消费端：根据学生id查询详情（从redis缓存取数据，如果没有再从mysql取，然后存入redis）");
        return "studentDetail";
    }

    //rabbitmq实验：再访问此接口，从mq中获取消息（其实不用单独访问，会自动触发回调）
    //这里先用json返回测试，不调用前端模板
    @RequestMapping(value = "/student/all/count")
    @RabbitListener(queuesToDeclare = @Queue("microservice-work"))
    @SendTo  //必须加这个注解，否则mq会报错，且死循环报错，或者方法改成void类型，但这里不适合改成void
    public @ResponseBody Object allStudentCount(String message){//添加参数message，它就是mq中的消息？
        Integer allStudentCount = studentService.queryStudentCount();
        log.info("消费端all/count接口：查询学生总人数。"); //打印在控制台

        //这里应该只需要消费消息，从mq中读取消息即可，假设这个业务需要根据前面的结果来判断是否执行，去mq查数据即可
        log.warn("消费端all/count 加了@RabbitListener的方法 rabbitmq message=" + message);

        return "学生总人数：" + allStudentCount; //返回到页面上
    }

    /**
     * 2022-02-11
     * 多个方法监听（@RabbitListener，模拟多个消费者)，一旦mq有消息就都会自动触发回调，从而消费消息，这里引入手动ack，看能否体现能者多劳
     * 补充：实际这里并不能体现手动ack的用处，之前的学习中知道手动确认的作用在于多消费者同时消费时能者多劳，如果不
     * 手动确认，就只能是平均分配消息，如果其中一个消费者消费途中挂掉，会造成消息丢失，但之前的例子是纯api方式，
     * 并且是work模型，没有用到交换机，消息不能重复消费。而我这里用的是routing模式中的动态路由，多消费者模式下，每个消费者都能
     * 消费到每条消息，不存在能者多劳的作用，所以这里只是把手动ack的用法熟悉一下，场景并不适用（之前的demo中没有测试过springboot结合mq
     * 时手动ack，这里就补充了），应该还是要在work模型下才能体现。
     *
     * 注意：经过测试，如果去掉手动确认，消息消费后会在unack一栏，停掉消费端和生产端服务后，会重新变为ready状态，
     * 下次消费启动服务后，消息会被自动消费（不过仍然是unack）
     * 先不管具体方法内容，可以运行就行
     * @param message
     * @param deliveryTag
     * @param channel
     * @return
     * @throws IOException
     */
    @RequestMapping(value = "/student/all/count2")
    @RabbitListener(bindings = {
            @QueueBinding(
                    value = @Queue(value = "microserviceDemo2", durable = "true", autoDelete = "false"),
                    exchange = @Exchange(value = "MicroServiceDemo_routingTopics_Exchange", type = ExchangeTypes.TOPIC),
                    key = {"user.save","user.*"}
                    )
            },ackMode = "MANUAL")
    @SendTo
    public @ResponseBody Object allStudentCount2(String message, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag, Channel channel) throws IOException {//添加参数message，它就是mq中的消息？
        Integer allStudentCount = studentService.queryStudentCount();
        log.info("消费端2 all/count接口：查询学生总人数。"); //打印在控制台

        //这里应该只需要消费消息，从mq中读取消息即可，假设这个业务需要根据前面的结果来判断是否执行，去mq查数据即可
        log.warn("消费端2 all/count 加了@RabbitListener的方法 rabbitmq message=" + message);

        if (message.contains("微服务")) {
            // RabbitMQ的ack机制中，第二个参数返回true，表示需要将这条消息投递给其他的消费者重新消费
            channel.basicQos(1);//补充：每次只消费1条消息，ps：但没有这句好像也不影响，因为routing模式，每个消费者都可接收并消费所有消息
            channel.basicAck(deliveryTag, false); //确认消息，如果没有，消息就是unack状态
        }
        else {
            // 第三个参数true，表示这个消息会重新进入队列
            channel.basicNack(deliveryTag, false, true);
        }
        return "学生总人数2：" + allStudentCount; //返回到页面上
    }

    /**
     * 2022-02-11
     * 同上，多个方法监听（@RabbitListener，模拟多个消费者)
     * @param message
     * @param deliveryTag
     * @param channel
     * @return
     * @throws IOException
     */
    @RequestMapping(value = "/student/all/count3")
    @RabbitListener(bindings = {
            @QueueBinding(
                    value = @Queue(value = "microserviceDemo3", durable = "true", autoDelete = "false"),
//                    value = @Queue,//之前的demo写法，应该就是默认的，临时队列，用完就删
                    exchange = @Exchange(value = "MicroServiceDemo_routingTopics_Exchange", type = ExchangeTypes.TOPIC),//type写成"topic"也可，这里这种写法应该是自动获取
                    key = {"user.#"}//匹配多个单词
            )
    },ackMode = "MANUAL")
    @SendTo
    public @ResponseBody Object allStudentCount3(String message, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag, Channel channel) throws IOException {//添加参数message，它就是mq中的消息？
        Integer allStudentCount = studentService.queryStudentCount();
        log.info("消费端3 all/count接口：查询学生总人数。"); //打印在控制台

        //这里应该只需要消费消息，从mq中读取消息即可，假设这个业务需要根据前面的结果来判断是否执行，去mq查数据即可
        log.warn("消费端3 all/count 加了@RabbitListener的方法 rabbitmq message=" + message);

        if (message.contains("微服务")) {
            // RabbitMQ的ack机制中，第二个参数返回true，表示需要将这条消息投递给其他的消费者重新消费
            channel.basicAck(deliveryTag, false);
        } else {
            // 第三个参数true，表示这个消息会重新进入队列
            channel.basicNack(deliveryTag, false, true);
        }
        return "学生总人数3：" + allStudentCount; //返回到页面上
    }
}
