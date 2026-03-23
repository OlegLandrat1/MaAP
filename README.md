## Пользовательский пул потоков

В данной работе представлена реализация пользовательского
пула потоков [CustomThreadPoolExecutor.java](src/main/java/org/example/CustomThreadPoolExecutor.java).

В соответствии с требованиями, [CustomThreadPoolExecutor.java](src/main/java/org/example/CustomThreadPoolExecutor.java)
имеет следующие настройки:

``corePoolSize`` - минимальное (базовое) количество потоков.
``maxPoolSize`` - максимальное количество потоков.
``keepAliveTime и timeUnit`` - время, в течении которого поток может простаивать до завершения и его еденицы измерения.
`` queueSize`` - ограничение кол-ва задач в очереди.
`` minSpareThreads`` - минимальное число резервных потоков, которые должны быть всегда доступны. Если число свободных потоков падает ниже данного значения, пул должен создавать новые потоки, в т.ч. при невысокой нагрузке.

### Обработка отказов

В Custom1ThreadPoolExecutor обработка отказа осуществляется в 3 этапа:

Этап 1: Распределение по локальным очередям
Метод: ``tryDistributeToWorkerQueues(Runnable command)``
Отказ, если все N локальных очередей переполнены → возвращает false
Размер каждой локальной очереди = queueSize

Этап 2: Попытка создать новый Worker поток. 

Метод: ``addWorker(Runnable firstTask)``
Отказ, если workerCount >= maxPoolSize → return false

Этап 3: Добавление в глобальную очередь

Метод: ``execute(Runnable command)``

Отказ, если и локальные очереди полны, и Worker'ов максимум, и глобальная очередь полна

Рис.1 Алгоритм обработки отказа
[](pictures/Screenshot from 2026-03-23 20-13-16.png)

### Распределение задач

Поступающие задачи распределяются по принципу Round Robin между несколькими очередями, привязанными к разным рабочим потокам 

Задача `execute(task1)`, присваивается первой по счету очереди рабочего потока `workerQueues[0].offer(task1)`.
Индекс воркера `nextWorkerIndex` для следующей задачи инкрементируется.
Следующая поступающая задача `execute(task2)` назначается воркеру `workerQueues[1]`.
Таким образом, на примере 3-х воркеров с размером очереди = 2, пул потоков будет иметь следующее состояние:

workerQueues[0] = [task1]   (1/2 заполнено)

workerQueues[1] = [task2]   (1/2 заполнено)

workerQueues[2] = []        (0/2 заполнено)

nextWorkerIndex = 2

```
private boolean tryDistributeToWorkerQueues(Runnable command) {
        if (workerQueues.isEmpty()) {
            return false;
        }

        int attemptCount = 0;
        int maxAttempts = workerQueues.size();

        while (attemptCount < maxAttempts) {
            int index = nextWorkerIndex.getAndIncrement() % workerQueues.size();
            BlockingQueue<Runnable> queue = workerQueues.get(index);

            // Пытаемся добавить в очередь без блокировки
            if (queue.offer(command)) {
                return true;
            }
            attemptCount++;
        }

        return false;
    }
```
Демонстрация работы CustomThreadPoolExecutor с элементами тестирования и логированием:
### ``Main.java``


### Сравнительный анализ производительности CustomThreadPoolExecutor и стандартного ThreadPool

Замеры, вычисления и запись метрик в файлы .csv реализованы в классах [PerformanceMeasurement.java](src/main/java/org/example/PerformanceMeasurement.java) и [StandardThreadPool.java](src/main/java/org/example/StandardThreadPool.java) для CustomThreadPool и стандартного ThreadPool соответственно.
Результаты сравнительного анализа представлены на графиках 1-4:
![](/home/oleg/IdeaProjects/MyThreadPool/pictures/png1.PNG)
![](/home/oleg/IdeaProjects/MyThreadPool/pictures/png2.PNG)
![](/home/oleg/IdeaProjects/MyThreadPool/pictures/png3.PNG)
![](/home/oleg/IdeaProjects/MyThreadPool/pictures/png4.PNG)
